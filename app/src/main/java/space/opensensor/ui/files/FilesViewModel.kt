package space.opensensor.ui.files

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import space.opensensor.archive.ArchiveIndex
import space.opensensor.archive.ArchiveStore
import space.opensensor.archive.FileExportSpace
import space.opensensor.archive.directoryEntries
import space.opensensor.data.DeviceConnectionManager
import space.opensensor.data.DeviceOwnership
import space.opensensor.data.ManifestState
import space.opensensor.data.ReconnectState
import space.opensensor.data.SyncProgress
import space.opensensor.data.ble.ConnectionState
import space.opensensor.data.ble.ThingStatus
import space.opensensor.data.ble.TransportKind
import space.opensensor.data.history.ArchiveQueryResult
import space.opensensor.data.history.DerivedStore
import space.opensensor.data.history.FileSummary
import space.opensensor.data.history.HistoryDeriver
import space.opensensor.data.history.HistoryRepository
import space.opensensor.data.history.MetricSpec
import space.opensensor.data.history.Metrics
import space.opensensor.data.history.archiveQueryResults
import space.opensensor.data.local.database.ArchivedFile
import space.opensensor.data.local.database.ArchivedFileDao
import space.opensensor.data.local.database.KnownDeviceDao
import space.opensensor.data.settings.AppSettings
import space.opensensor.data.settings.DetailLevel
import space.opensensor.family.air.AirMetrics
import space.opensensor.files.DeviceFiles
import space.opensensor.files.FileMetricsController
import space.opensensor.files.FilesController
import space.opensensor.storage.DerivedQuota
import space.opensensor.ui.components.ScreenMessage
import java.io.File
import javax.inject.Inject

/**
 * The device side of the Files screen. The archive list and every export live in the shared
 * [FilesController]. The listing, plan and transfer progress are in [FilesViewModel.deviceFiles], so a
 * progress tick never rebuilds this state (#63).
 */
@Immutable
data class FilesUiState(
    val connection: ConnectionState = ConnectionState.Disconnected,
    /** A transfer is running. */
    val syncRunning: Boolean = false,
    val reconnecting: ReconnectState? = null,
    /** A background worker or Collect all holds this device right now, see [ThingStatus]. */
    val heldInBackground: Boolean = false,
    /** Active pipe, for the top-bar chip (same source as Today / Device). */
    val transport: TransportKind? = null,
    /** Simple shows times, sizes and states in words. Detailed adds the chart and inspector, Developer file names and transfer internals. */
    val detailLevel: DetailLevel = DetailLevel.SIMPLE,
    /** "Chart by file": one column per archived file of the connected thing (null while off). */
    val fileChart: FileChartState? = null,
) {
    /** The status every thing page reads, the same rule Today and Device use. */
    val thing: ThingStatus get() = ThingStatus(connection, reconnecting != null, syncRunning, heldInBackground)
}

/** The strip of per-file columns: the window shown, of how many files, for which metric. */
@Immutable
data class FileChartState(
    val metric: MetricSpec,
    /** The chips offered, from [fileChartMetrics]. */
    val metrics: List<MetricSpec> = Metrics.all,
    /** Every telemetry file of the thing, oldest first (timed by first row, then untimed by name). */
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
internal data class FileSummaryRequest(val thing: String, val files: List<ArchivedFile>)

/**
 * #92. The metrics the Files chart offers, the same rule as History. Corrected PM2.5 comes first and
 * is the default. Once the files are read and none has a humidity reading, corrected is dropped and raw
 * leads. Simple never offers raw next to corrected, Detailed always does. Simple offers only the
 * profile primary metrics, Detailed every metric.
 */
internal fun fileChartMetrics(summaries: List<FileSummary>, detailed: Boolean): List<MetricSpec> {
    val corrected = summaries.isEmpty() || summaries.any { (it.agg(AirMetrics.PM25_CORRECTED)?.n ?: 0) > 0 }
    return Metrics.all.filter { m ->
        when (m) {
            AirMetrics.PM25_CORRECTED -> corrected
            AirMetrics.PM25 -> detailed || !corrected
            else -> detailed || m.primary
        }
    }
}

internal fun fileChartState(
    choice: MetricSpec?,
    detailed: Boolean,
    offset: Int?,
    request: FileSummaryRequest?,
    result: ArchiveQueryResult<FileSummaryRequest?, List<FileSummary>>,
): FileChartState {
    val list = result.forQuery(request).orEmpty()
    val lastPageStart = maxOf(0, list.size - FilesViewModel.FILE_CHART_PAGE)
    val start = (offset ?: lastPageStart).coerceIn(0, lastPageStart)
    val metrics = fileChartMetrics(list, detailed)
    return FileChartState(
        // A choice the level no longer offers (raw after switching to Simple) falls back to the default.
        metric = choice?.takeIf { it in metrics } ?: metrics.first(),
        metrics = metrics,
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
    deriver: HistoryDeriver,
    quota: DerivedQuota,
    settings: AppSettings,
) : ViewModel() {
    private val repository = manager.controller(deviceAddress)
    private val enrolledDevice = knownDeviceDao.observeAll().map { devices -> devices.firstOrNull { it.address == deviceAddress } }

    /** Storage owner from INFO or enrollment, never the thing embedded in a retained SD pathname. */
    private val archiveThing = combine(repository.info, enrolledDevice) { info, enrolled -> info?.thing ?: enrolled?.thing }.distinctUntilChanged()

    @AssistedFactory
    interface Factory {
        fun create(deviceAddress: String): FilesViewModel
    }

    /**
     * The archive list and the exports, shared with iOS. Staged copies go to `cache/export/<run>`,
     * which the FileProvider serves. The daily zip reads the derived `derived/v1` tree, after
     * rebuilding the days the cache quota or the OS evicted. The quota waits until the zip is staged (#61).
     */
    val files = FilesController(
        scope = viewModelScope,
        store = store,
        index = index,
        space = FileExportSpace(File(context.cacheDir, "export")),
        owner = archiveThing,
        daily = {
            deriver.ensureDailyFiles()
            directoryEntries(derived.root, "derived/v1")
        },
        holdDaily = { block -> quota.holding { block() } },
    )

    val events: Flow<ScreenMessage> = repository.events.map(ScreenMessage::Device)

    private val fileChartOn = MutableStateFlow(false)

    /** The chip the user picked, null until then so the chart opens on the default of [fileChartMetrics]. */
    private val fileChartMetric = MutableStateFlow<MetricSpec?>(null)

    /** Null = the latest page; otherwise the offset the user paged to. */
    private val fileChartOffset = MutableStateFlow<Int?>(null)

    /**
     * Summaries follow the archive: whenever the connected thing's file list changes while the chart
     * is on, the missing files are read (cached per `(name, crc32)` in the repository, so a new sync
     * costs only its new files).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val fileSummaryRequest: StateFlow<FileSummaryRequest?> = combine(fileChartOn, archiveThing) { on, thing -> thing.takeIf { on } }
        .distinctUntilChanged()
        .flatMapLatest { thing ->
            // Only while the chart is on, and only the connected thing's rows.
            if (thing == null) flowOf(null) else archivedFileDao.observeForThing(thing).map { FileSummaryRequest(thing, it) }
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val fileSummaries: StateFlow<ArchiveQueryResult<FileSummaryRequest?, List<FileSummary>>> = fileSummaryRequest.archiveQueryResults { request ->
        if (request == null) {
            flowOf<List<FileSummary>?>(null)
        } else {
            flow<List<FileSummary>?> { emit(history.fileSummaries(request.thing, request.files)) }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ArchiveQueryResult<FileSummaryRequest?, List<FileSummary>>(null, null))

    private val fileChart: Flow<FileChartState?> = combine(
        fileChartOn,
        combine(fileChartMetric, settings.detailLevel) { metric, level -> metric to level.detailed },
        fileChartOffset,
        fileSummaryRequest,
        fileSummaries,
    ) { on, (metric, detailed), offset, request, result ->
        if (on) fileChartState(metric, detailed, offset, request, result) else null
    }

    val uiState: StateFlow<FilesUiState> = combine(
        repository.connectionState,
        repository.sync.map { it.running }.distinctUntilChanged(),
        repository.reconnecting,
        repository.activeTransport,
        settings.detailLevel,
        fileChart,
        repository.ownership,
    ) { values ->
        FilesUiState(
            connection = values[0] as ConnectionState,
            syncRunning = values[1] as Boolean,
            reconnecting = values[2] as ReconnectState?,
            transport = values[3] as TransportKind?,
            detailLevel = values[4] as DetailLevel,
            fileChart = values[5] as FileChartState?,
            heldInBackground = values[6] == DeviceOwnership.BACKGROUND,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FilesUiState())

    /** Transfer bytes are collected inside the shared progress card, without invalidating the screen. */
    val syncProgress: StateFlow<SyncProgress> = repository.sync

    private val syncControls = repository.sync.map { SyncProgress(running = it.running, outcomes = it.outcomes) }.distinctUntilChanged()

    /** The listing, sampled plan and sync control flags, read by the shared device file list. */
    val deviceFiles: StateFlow<DeviceFiles> = combine(repository.connectionState, repository.manifest, repository.plan, syncControls) { connection, manifest, plan, sync ->
        DeviceFiles(ready = connection.isReady, manifest = manifest, plan = plan, sync = sync)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DeviceFiles(ready = false, manifest = ManifestState.Idle, plan = null, sync = SyncProgress()))

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
