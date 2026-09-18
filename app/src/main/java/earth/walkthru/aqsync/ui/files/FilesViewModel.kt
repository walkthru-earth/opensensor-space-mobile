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
import earth.walkthru.aqsync.data.history.FileSummary
import earth.walkthru.aqsync.data.history.HistoryDeriver
import earth.walkthru.aqsync.data.history.HistoryIndexer
import earth.walkthru.aqsync.data.history.HistoryRepository
import earth.walkthru.aqsync.data.history.MetricSpec
import earth.walkthru.aqsync.data.history.Metrics
import earth.walkthru.aqsync.data.local.database.ArchivedFile
import earth.walkthru.aqsync.data.local.database.ArchivedFileDao
import earth.walkthru.aqsync.data.local.database.KnownDevice
import earth.walkthru.aqsync.data.settings.AppSettings
import earth.walkthru.aqsync.data.sync.SyncPlan
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

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
    /** "Chart by file": one column per archived file of the connected station (null while off). */
    val fileChart: FileChartState? = null,
)

/** The strip of per-file columns: the window shown, of how many files, for which metric. */
data class FileChartState(
    val metric: MetricSpec,
    /** Every telemetry file of the station, oldest first (timed by first row, then untimed by name). */
    val total: Int,
    /** Index of the first column shown into the full list. */
    val offset: Int,
    val page: List<FileSummary>,
    val loading: Boolean,
) {
    val canPageBack: Boolean get() = offset > 0
    val canPageForward: Boolean get() = offset + page.size < total
}

/** UI-side effects: launch a share sheet or a SAF picker. */
sealed interface FilesEffect {
    data class Launch(val intent: Intent) : FilesEffect
    data class CreateDocument(val suggestedName: String) : FilesEffect
}

@HiltViewModel
class FilesViewModel @Inject constructor(
    private val repository: DeviceConnectionRepository,
    private val archivedFileDao: ArchivedFileDao,
    private val history: HistoryRepository,
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

    private val fileChartOn = MutableStateFlow(false)
    private val fileChartMetric = MutableStateFlow(Metrics.primary)

    /** Null = the latest page; otherwise the offset the user paged to. */
    private val fileChartOffset = MutableStateFlow<Int?>(null)
    private val summaries = MutableStateFlow<Pair<String, List<FileSummary>>?>(null)
    private val summarising = MutableStateFlow(false)

    init {
        // Files synced before Room v4 (or by an older reader) get their history index here: idempotent,
        // a few hundred files take well under a second, and an interrupted pass simply resumes next time.
        viewModelScope.launch {
            runCatching { historyIndexer.backfillAll() }
            runCatching { historyDeriver.deriveStale() }
        }
    }

    /**
     * Summaries follow the archive: whenever the connected station's file list changes while the chart
     * is on, the missing files are read (cached per `(name, crc32)` in the repository, so a new sync
     * costs only its new files).
     */
    private val fileSummaryUpdates = combine(fileChartOn, repository.currentKnownDevice.map { it?.station }.distinctUntilChanged(), archivedFileDao.observeAll()) { on, station, files ->
        if (!on || station == null) null else station to files.filter { it.station == station }.map { Triple(it.name, it.crc32, it.estFirstUtcNs) to it }
    }.distinctUntilChanged { a, b -> a?.first == b?.first && a?.second?.map { it.first } == b?.second?.map { it.first } }
        .onEach { input ->
            if (input == null) { summaries.value = null; return@onEach }
            summarising.value = true
            try {
                summaries.value = input.first to history.fileSummaries(input.first, input.second.map { it.second })
            } finally {
                summarising.value = false
            }
        }

    init {
        // After the property above exists: initializer order matters.
        fileSummaryUpdates.launchIn(viewModelScope)
    }

    private val fileChart: Flow<FileChartState?> = combine(fileChartOn, fileChartMetric, fileChartOffset, summaries, summarising) { on, metric, offset, all, loading ->
        if (!on) return@combine null
        val list = all?.second ?: emptyList()
        val start = (offset ?: (list.size - FILE_CHART_PAGE)).coerceIn(0, maxOf(0, list.size - 1))
        FileChartState(metric, list.size, start, list.drop(start).take(FILE_CHART_PAGE), loading)
    }

    val uiState: StateFlow<FilesUiState> = combine(
        repository.connectionState, repository.manifest, repository.plan, repository.sync, archivedFileDao.observeAll(), exporting,
        repository.reconnecting, repository.currentKnownDevice, autoSyncScheduler.observeActive(), repository.activeTransport,
        settings.detailed, fileChart,
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
            fileChart = values[11] as FileChartState?,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FilesUiState())

    fun setFileChart(on: Boolean) { fileChartOn.value = on; if (!on) fileChartOffset.value = null }
    fun setFileChartMetric(metric: MetricSpec) { fileChartMetric.value = metric }

    /** Page the strip by one window; forward past the end snaps back to "latest". */
    fun pageFileChart(direction: Int) {
        val st = uiState.value.fileChart ?: return
        val next = st.offset + direction * FILE_CHART_PAGE
        fileChartOffset.value = if (next + FILE_CHART_PAGE >= st.total) null else next.coerceAtLeast(0)
    }

    /** Headers are local calendar days (the device names and partitions files in UTC); see [FileGrouping]. */
    private fun group(plan: SyncPlan): List<PartitionGroup> = FileGrouping.group(plan.entries)

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

    /** Share the derived daily files (one Parquet per station-day) — the small export for a data collector. */
    fun shareDailyZip() {
        viewModelScope.launch {
            exporting.value = true
            try {
                val zip = export.buildDailyZip()
                if (zip == null) {
                    localEvents.tryEmit(DeviceEvent.Info("No daily files yet: they are built from synced files that carry a timestamp."))
                    return@launch
                }
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

    companion object {
        /** Columns per page of the "chart by file" strip: a day of 15-min files, tappable at phone width. */
        const val FILE_CHART_PAGE = 96
    }
}
