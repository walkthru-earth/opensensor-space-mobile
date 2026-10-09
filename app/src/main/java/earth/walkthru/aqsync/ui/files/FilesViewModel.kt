package earth.walkthru.aqsync.ui.files

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import earth.walkthru.aqsync.R
import earth.walkthru.aqsync.archive.ArchiveIndex
import earth.walkthru.aqsync.archive.ArchiveStore
import earth.walkthru.aqsync.archive.FileExportSpace
import earth.walkthru.aqsync.archive.directoryEntries
import earth.walkthru.aqsync.data.DeviceConnectionManager
import earth.walkthru.aqsync.data.ManifestState
import earth.walkthru.aqsync.data.ReconnectState
import earth.walkthru.aqsync.data.SyncProgress
import earth.walkthru.aqsync.data.background.AutoSyncScheduler
import earth.walkthru.aqsync.data.ble.ConnectionState
import earth.walkthru.aqsync.data.ble.TransportKind
import earth.walkthru.aqsync.data.history.ArchiveQueryResult
import earth.walkthru.aqsync.data.history.DerivedStore
import earth.walkthru.aqsync.data.history.FileSummary
import earth.walkthru.aqsync.data.history.HistoryRepository
import earth.walkthru.aqsync.data.history.MetricSpec
import earth.walkthru.aqsync.data.history.Metrics
import earth.walkthru.aqsync.data.history.archiveQueryResults
import earth.walkthru.aqsync.data.local.database.ArchivedFile
import earth.walkthru.aqsync.data.local.database.ArchivedFileDao
import earth.walkthru.aqsync.data.local.database.KnownDevice
import earth.walkthru.aqsync.data.local.database.KnownDeviceDao
import earth.walkthru.aqsync.data.settings.AppSettings
import earth.walkthru.aqsync.data.settings.DetailLevel
import earth.walkthru.aqsync.data.sync.SyncPlan
import earth.walkthru.aqsync.ui.components.ScreenMessage
import earth.walkthru.opensensor.files.FileMetricsController
import earth.walkthru.opensensor.files.FilesController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/** The device side of the Files screen. The archive list and every export live in the shared [FilesController]. */
data class FilesUiState(
    val connection: ConnectionState = ConnectionState.Disconnected,
    val manifest: ManifestState = ManifestState.Idle,
    val plan: SyncPlan? = null,
    val sync: SyncProgress = SyncProgress(),
    val reconnecting: ReconnectState? = null,
    /** Auto-sync switch of the connected device (null: no session / unknown device). */
    val autoSync: Boolean? = null,
    /** A background sync job is queued or running right now. */
    val backgroundSyncActive: Boolean = false,
    /** Active pipe, for the top-bar chip (same source as Today / Device). */
    val transport: TransportKind? = null,
    /** Simple shows times, sizes and states in words. Detailed adds the chart and inspector, Developer file names and transfer internals. */
    val detailLevel: DetailLevel = DetailLevel.SIMPLE,
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

/** Ties an asynchronous chart read to both its storage owner and the exact archive snapshot. */
internal data class FileSummaryRequest(val station: String, val files: List<ArchivedFile>)

internal fun fileChartState(
    metric: MetricSpec,
    offset: Int?,
    request: FileSummaryRequest?,
    result: ArchiveQueryResult<FileSummaryRequest?, List<FileSummary>>,
): FileChartState {
    val list = result.forQuery(request).orEmpty()
    val lastPageStart = maxOf(0, list.size - FilesViewModel.FILE_CHART_PAGE)
    val start = (offset ?: lastPageStart).coerceIn(0, lastPageStart)
    return FileChartState(
        metric = metric,
        total = list.size,
        offset = start,
        page = list.drop(start).take(FilesViewModel.FILE_CHART_PAGE),
        loading = request != null && (result.query != request || result.value == null),
    )
}

@HiltViewModel(assistedFactory = FilesViewModel.Factory::class)
class FilesViewModel @AssistedInject constructor(
    @param:ApplicationContext private val context: Context,
    manager: DeviceConnectionManager,
    @Assisted deviceAddress: String,
    private val archivedFileDao: ArchivedFileDao,
    knownDeviceDao: KnownDeviceDao,
    private val history: HistoryRepository,
    store: ArchiveStore,
    index: ArchiveIndex,
    derived: DerivedStore,
    private val autoSyncScheduler: AutoSyncScheduler,
    settings: AppSettings,
) : ViewModel() {
    private val repository = manager.controller(deviceAddress)
    private val enrolledDevice = knownDeviceDao.observeAll().map { devices -> devices.firstOrNull { it.address == deviceAddress } }

    /** Storage owner from INFO or enrollment, never the station embedded in a retained SD pathname. */
    private val archiveStation = combine(repository.info, enrolledDevice) { info, enrolled -> info?.station ?: enrolled?.station }.distinctUntilChanged()

    @AssistedFactory
    interface Factory {
        fun create(deviceAddress: String): FilesViewModel
    }

    /**
     * The archive list and the exports, shared with iOS. Staged copies go to `cache/export/<run>`,
     * which the FileProvider serves. The daily zip reads the derived `derived/v1` tree.
     */
    val files = FilesController(
        scope = viewModelScope,
        store = store,
        index = index,
        space = FileExportSpace(File(context.cacheDir, "export")),
        owner = archiveStation,
        daily = { directoryEntries(derived.root, "derived/v1") },
    )

    private val localEvents = MutableSharedFlow<ScreenMessage>(extraBufferCapacity = 4)
    val events: Flow<ScreenMessage> = merge(repository.events.map(ScreenMessage::Device), localEvents)

    private val fileChartOn = MutableStateFlow(false)
    private val fileChartMetric = MutableStateFlow(Metrics.primary)

    /** Null = the latest page; otherwise the offset the user paged to. */
    private val fileChartOffset = MutableStateFlow<Int?>(null)

    /**
     * Summaries follow the archive: whenever the connected station's file list changes while the chart
     * is on, the missing files are read (cached per `(name, crc32)` in the repository, so a new sync
     * costs only its new files).
     */
    private val fileSummaryRequest: StateFlow<FileSummaryRequest?> = combine(fileChartOn, archiveStation, archivedFileDao.observeAll()) { on, station, files ->
        if (!on || station == null) null else FileSummaryRequest(station, files.filter { it.station == station })
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val fileSummaries: StateFlow<ArchiveQueryResult<FileSummaryRequest?, List<FileSummary>>> = fileSummaryRequest.archiveQueryResults { request ->
        if (request == null) {
            flowOf<List<FileSummary>?>(null)
        } else {
            flow<List<FileSummary>?> { emit(history.fileSummaries(request.station, request.files)) }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ArchiveQueryResult<FileSummaryRequest?, List<FileSummary>>(null, null))

    private val fileChart: Flow<FileChartState?> = combine(fileChartOn, fileChartMetric, fileChartOffset, fileSummaryRequest, fileSummaries) { on, metric, offset, request, result ->
        if (on) fileChartState(metric, offset, request, result) else null
    }

    val uiState: StateFlow<FilesUiState> = combine(
        repository.connectionState, repository.manifest, repository.plan, repository.sync, repository.reconnecting,
        repository.currentKnownDevice, autoSyncScheduler.observeActive(), repository.activeTransport, settings.detailLevel, fileChart,
    ) { values ->
        FilesUiState(
            connection = values[0] as ConnectionState,
            manifest = values[1] as ManifestState,
            plan = values[2] as SyncPlan?,
            sync = values[3] as SyncProgress,
            reconnecting = values[4] as ReconnectState?,
            autoSync = (values[5] as KnownDevice?)?.autoSync,
            backgroundSyncActive = values[6] as Boolean,
            transport = values[7] as TransportKind?,
            detailLevel = values[8] as DetailLevel,
            fileChart = values[9] as FileChartState?,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FilesUiState())

    fun setFileChart(on: Boolean) {
        fileChartOn.value = on
        if (!on) fileChartOffset.value = null
    }
    fun setFileChartMetric(metric: MetricSpec) {
        fileChartMetric.value = metric
    }

    /** Page the strip by one window; forward past the end snaps back to "latest". */
    fun pageFileChart(direction: Int) {
        val st = uiState.value.fileChart ?: return
        val next = st.offset + direction * FILE_CHART_PAGE
        fileChartOffset.value = if (next + FILE_CHART_PAGE >= st.total) null else next.coerceAtLeast(0)
    }

    fun refresh() = repository.refreshManifest()
    fun syncAll() = repository.syncAll()

    /** Quick toggle of the connected device's Auto-sync switch (same row the Device screen edits). */
    fun setAutoSync(enabled: Boolean) {
        val addr = repository.currentAddress ?: return
        viewModelScope.launch {
            repository.setAutoSync(addr, enabled)
            localEvents.tryEmit(ScreenMessage.Text(if (enabled) context.getString(R.string.files_autosync_event_on, AutoSyncScheduler.PERIOD_MINUTES.toInt()) else context.getString(R.string.files_autosync_event_off)))
        }
    }

    /**
     * One expedited background run for the connected device, so the user can leave the app right
     * away. The worker refuses to touch a device the app is connected to, so the current session is
     * released first; the worker's own session then feeds the same progress state this screen shows.
     */
    fun syncNowInBackground() {
        val addr = repository.currentAddress ?: return
        viewModelScope.launch {
            repository.disconnectNow()
            autoSyncScheduler.syncNow(addr)
            localEvents.tryEmit(ScreenMessage.Text(context.getString(R.string.files_bg_event)))
        }
    }
    fun syncOne(name: String) = repository.syncOne(name)
    fun cancelSync() = repository.cancelSync()
    fun disconnect() = repository.disconnect()

    companion object {
        /** Columns per page of the "chart by file" strip: a day of 15-min files, tappable at phone width. */
        const val FILE_CHART_PAGE = 96
    }
}

/** The shared inspector of one archived file, read through `:core:parquet` like iOS. */
@HiltViewModel
class FileMetricsViewModel @Inject constructor(store: ArchiveStore, settings: AppSettings) : ViewModel() {
    val metrics = FileMetricsController(viewModelScope, store)

    /** Detailed shows rows, time span and columns. Developer adds footer, codec, schema, row groups and metadata. */
    val detailLevel: StateFlow<DetailLevel> = settings.detailLevel.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailLevel.DETAILED)
}
