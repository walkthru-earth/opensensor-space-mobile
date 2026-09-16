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
import earth.walkthru.aqsync.data.ble.ConnectionState
import earth.walkthru.aqsync.data.export.ExportManager
import earth.walkthru.aqsync.data.local.database.ArchivedFile
import earth.walkthru.aqsync.data.local.database.ArchivedFileDao
import earth.walkthru.aqsync.data.sync.HivePartition
import earth.walkthru.aqsync.data.sync.ManifestEntry
import earth.walkthru.aqsync.data.sync.SyncPlan
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
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
) : ViewModel() {

    val events: SharedFlow<DeviceEvent> = repository.events

    private val _effects = MutableSharedFlow<FilesEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<FilesEffect> = _effects.asSharedFlow()

    private val exporting = MutableStateFlow(false)
    private var pendingZip: File? = null

    val uiState: StateFlow<FilesUiState> = combine(
        repository.connectionState, repository.manifest, repository.plan, repository.sync, archivedFileDao.observeAll(), exporting,
        repository.reconnecting,
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
