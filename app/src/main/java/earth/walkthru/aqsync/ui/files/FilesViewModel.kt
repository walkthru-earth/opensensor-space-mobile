package earth.walkthru.aqsync.ui.files

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import earth.walkthru.aqsync.data.DeviceConnectionRepository
import earth.walkthru.aqsync.data.DeviceEvent
import earth.walkthru.aqsync.data.ManifestState
import earth.walkthru.aqsync.data.ReconnectState
import earth.walkthru.aqsync.data.SyncProgress
import earth.walkthru.aqsync.data.background.AutoSyncScheduler
import earth.walkthru.aqsync.data.ble.ConnectionState
import earth.walkthru.aqsync.data.ble.TransportKind
import earth.walkthru.aqsync.data.export.ExportManager
import earth.walkthru.aqsync.data.history.HistoryDeriver
import earth.walkthru.aqsync.data.history.HistoryIndexer
import earth.walkthru.aqsync.data.local.database.ArchivedFile
import earth.walkthru.aqsync.data.local.database.ArchivedFileDao
import earth.walkthru.aqsync.data.settings.AppSettings
import earth.walkthru.aqsync.data.local.database.KnownDevice
import earth.walkthru.aqsync.data.sync.HivePartition
import earth.walkthru.aqsync.data.sync.ManifestEntry
import earth.walkthru.aqsync.data.sync.SyncPlan
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/** A group of manifest entries sharing a Hive partition, in display order. */
data class PartitionGroup(val partition: HivePartition, val title: String, val subtitle: String?, val entries: List<ManifestEntry>) {
    val bytes: Long get() = entries.sumOf { it.bytes }
}

data class FilesUiState(
    val connection: ConnectionState = ConnectionState.Disconnected,
    val manifest: ManifestState = ManifestState.Idle,
    val plan: SyncPlan? = null,
    val groups: List<PartitionGroup> = emptyList(),
    val sync: SyncProgress = SyncProgress(),
    val archived: List<ArchivedFile> = emptyList(),
    val archivedBytes: Long = 0,
    val exporting: Boolean = false,
    val reconnecting: ReconnectState? = null,
    /** Auto-sync switch of the connected device (null: no session / unknown device). */
    val autoSync: Boolean? = null,
    /** A background sync job is queued or running right now. */
    val backgroundSyncActive: Boolean = false,
    /** Active pipe, for the top-bar chip (same source as Today / Device). */
    val transport: TransportKind? = null,
    /** Detailed level (or Developer) reveals the per-file "Metrics" inspector (the reader's on-phone proof). */
    val advancedMode: Boolean = false,
)

/** UI-side effects: launch a share sheet or a SAF picker. */
sealed interface FilesEffect {
    data class Launch(val intent: Intent) : FilesEffect
    data class CreateDocument(val suggestedName: String) : FilesEffect
}

@HiltViewModel
class FilesViewModel @Inject constructor(
    private val repository: DeviceConnectionRepository,
    private val archivedFileDao: ArchivedFileDao,
    private val export: ExportManager,
    private val autoSyncScheduler: AutoSyncScheduler,
    settings: AppSettings,
    historyIndexer: HistoryIndexer,
    historyDeriver: HistoryDeriver,
) : ViewModel() {

    private val localEvents = MutableSharedFlow<DeviceEvent>(extraBufferCapacity = 4)
    val events: Flow<DeviceEvent> = merge(repository.events, localEvents)

    private val _effects = MutableSharedFlow<FilesEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<FilesEffect> = _effects.asSharedFlow()

    private val exporting = MutableStateFlow(false)
    private var pendingZip: File? = null

    init {
        // Files synced before Room v4 (or by an older reader) get their history index here: idempotent,
        // a few hundred files take well under a second, and an interrupted pass simply resumes next time.
        viewModelScope.launch {
            runCatching { historyIndexer.backfillAll() }
            runCatching { historyDeriver.deriveStale() }
        }
    }

    val uiState: StateFlow<FilesUiState> = combine(
        repository.connectionState, repository.manifest, repository.plan, repository.sync, archivedFileDao.observeAll(), exporting,
        repository.reconnecting, repository.currentKnownDevice, autoSyncScheduler.observeActive(), repository.activeTransport,
        settings.detailed,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val archived = values[4] as List<ArchivedFile>
        val plan = values[2] as SyncPlan?
        FilesUiState(
            connection = values[0] as ConnectionState,
            manifest = values[1] as ManifestState,
            plan = plan,
            groups = plan?.let(::group) ?: emptyList(),
            sync = values[3] as SyncProgress,
            archived = archived,
            archivedBytes = archived.sumOf { it.bytes },
            exporting = values[5] as Boolean,
            reconnecting = values[6] as ReconnectState?,
            autoSync = (values[7] as KnownDevice?)?.autoSync,
            backgroundSyncActive = values[8] as Boolean,
            transport = values[9] as TransportKind?,
            advancedMode = values[10] as Boolean,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FilesUiState())

    private fun group(plan: SyncPlan): List<PartitionGroup> {
        val byPartition = plan.entries.groupBy { it.partition }
        return byPartition.map { (p, entries) ->
            val (title, subtitle) = when (p) {
                is HivePartition.Dated -> p.isoDate to "station ${p.station.take(8)}"
                is HivePartition.Unsynced -> "Unsynced (no UTC)" to "boot ${p.boot.take(12)}"
                is HivePartition.Benchmarks -> "Benchmarks" to p.boot?.let { "boot ${it.take(12)}" }
                HivePartition.Legacy -> "Legacy Parquet" to null
                is HivePartition.Other -> (p.directory.ifEmpty { "(root)" }) to "unrecognised layout"
            }
            PartitionGroup(p, title, subtitle, entries.sortedByDescending { it.name })
        }.sortedWith(
            compareBy<PartitionGroup> { rank(it.partition) }
                .thenByDescending { (it.partition as? HivePartition.Dated)?.isoDate ?: "" }
                .thenBy { it.title }
        )
    }

    private fun rank(p: HivePartition) = when (p) {
        is HivePartition.Dated -> 0
        is HivePartition.Unsynced -> 1
        is HivePartition.Benchmarks -> 2
        HivePartition.Legacy -> 3
        is HivePartition.Other -> 4
    }

    fun refresh() = repository.refreshManifest()
    fun syncAll() = repository.syncAll()

    /** Quick toggle of the connected device's Auto-sync switch (same row the Device screen edits). */
    fun setAutoSync(enabled: Boolean) {
        val addr = repository.currentAddress ?: return
        viewModelScope.launch {
            repository.setAutoSync(addr, enabled)
            localEvents.tryEmit(DeviceEvent.Info(if (enabled) "Auto-sync on: about every ${AutoSyncScheduler.PERIOD_MINUTES} min, app closed or not." else "Auto-sync off for this device."))
        }
    }

    /**
     * One expedited background run for the connected device — the user can leave the app right
     * away. The worker refuses to touch a device the app is connected to, so the current session is
     * released first; the worker's own session then feeds the same progress state this screen shows.
     */
    fun syncNowInBackground() {
        val addr = repository.currentAddress ?: return
        viewModelScope.launch {
            repository.disconnectNow()
            autoSyncScheduler.syncNow(addr)
            localEvents.tryEmit(DeviceEvent.Info("Background sync queued. You can leave the app; the notification shows progress."))
        }
    }
    fun syncOne(name: String) = repository.syncOne(name)
    fun cancelSync() = repository.cancelSync()
    fun disconnect() = repository.disconnect()

    fun share(file: ArchivedFile) {
        export.shareIntent(file)?.let { _effects.tryEmit(FilesEffect.Launch(it)) }
    }

    fun shareAllLoose() {
        val files = uiState.value.archived
        export.shareIntent(files)?.let { _effects.tryEmit(FilesEffect.Launch(it)) }
    }

    fun shareZip() {
        viewModelScope.launch {
            exporting.value = true
            try {
                val zip = export.buildArchiveZip() ?: return@launch
                _effects.tryEmit(FilesEffect.Launch(export.shareZipIntent(zip)))
            } finally {
                exporting.value = false
            }
        }
    }

    /** Step 1 of "Save to…": build the zip, then ask the UI to open the SAF picker. */
    fun saveZip() {
        viewModelScope.launch {
            exporting.value = true
            try {
                val zip = export.buildArchiveZip() ?: return@launch
                pendingZip = zip
                _effects.tryEmit(FilesEffect.CreateDocument(zip.name))
            } finally {
                exporting.value = false
            }
        }
    }

    /** Step 2: the picker returned a destination. */
    fun onDocumentCreated(uri: Uri?) {
        val zip = pendingZip ?: return
        pendingZip = null
        if (uri == null) return
        viewModelScope.launch {
            exporting.value = true
            try {
                export.copyTo(zip, uri)
            } finally {
                exporting.value = false
            }
        }
    }

    fun archivedFor(name: String, station: String?): ArchivedFile? =
        uiState.value.archived.firstOrNull { it.name == name && (station == null || it.station == station) }
}
