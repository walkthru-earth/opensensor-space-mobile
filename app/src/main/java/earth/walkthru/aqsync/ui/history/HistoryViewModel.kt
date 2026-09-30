package earth.walkthru.aqsync.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import earth.walkthru.aqsync.data.DeviceConnectionManager
import earth.walkthru.aqsync.data.ble.ConnectionState
import earth.walkthru.aqsync.data.ble.TransportKind
import earth.walkthru.aqsync.data.history.ArchiveQueryResult
import earth.walkthru.aqsync.data.history.ArchiveSeriesQuery
import earth.walkthru.aqsync.data.history.HistoryRange
import earth.walkthru.aqsync.data.history.HistoryRepository
import earth.walkthru.aqsync.data.history.HistorySeries
import earth.walkthru.aqsync.data.history.HistoryWindow
import earth.walkthru.aqsync.data.history.MetricSpec
import earth.walkthru.aqsync.data.history.Metrics
import earth.walkthru.aqsync.data.history.archiveQueryResults
import earth.walkthru.aqsync.data.local.database.ArchivedFileDao
import earth.walkthru.aqsync.data.local.database.KnownDeviceDao
import earth.walkthru.aqsync.data.local.database.MetricBucketDao
import earth.walkthru.aqsync.data.local.database.StationEpochDao
import earth.walkthru.aqsync.data.settings.AppSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId

data class HistoryUiState(
    /** Initial Room lookups and the selected archive query have not finished yet. */
    val initializing: Boolean = true,
    val station: String? = null,
    val stations: List<String> = emptyList(),
    val deviceName: String? = null,
    val metric: MetricSpec = Metrics.PM25,
    val availableMetrics: List<MetricSpec> = emptyList(),
    val range: HistoryRange = HistoryRange.DAY,
    val zone: ZoneId = ZoneId.systemDefault(),
    /** End of the visible span (epoch seconds); "now" rounded up unless the user paged back. */
    val toS: Long = 0,
    val series: HistorySeries? = null,
    /** Verified telemetry files with UTC rows / all verified files of the station. */
    val filesWithTime: Int = 0,
    val filesTotal: Int = 0,
    val filesPendingIndex: Int = 0,
    val filesIndexFailed: Int = 0,
    val filesOtherStation: Int = 0,
    val filesUntimed: Int = 0,
    /** Files placed on the timeline by a §4.6 estimate. */
    val filesEstimated: Int = 0,
    val daysWithData: Int = 0,
    val firstDayS: Long? = null,
    val lastDayS: Long? = null,
    val advancedMode: Boolean = false,
    val connection: ConnectionState = ConnectionState.Disconnected,
    val transport: TransportKind? = null,
) {
    val window: HistoryWindow get() = HistoryWindow.endingAt(toS, range, zone)
    val canPageForward: Boolean get() = toS < nowRounded(range, zone)
}

/**
 * The end of the span that contains "now": the next **local midnight**. Every range is then whole
 * local days — a Day is a calendar day (the ring's dial has midnight at the top), a Week seven of
 * them ending tonight, and so on. The buckets underneath stay UTC-aligned.
 */
fun nowRounded(range: HistoryRange, zone: ZoneId = ZoneId.systemDefault()): Long = HistoryWindow.current(range, zone).toS

/**
 * History works from the archive alone — no session needed — but shows the link chip when one exists.
 * The station is looked up from the known device; a logger that was never connected has no station
 * and therefore no history yet.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = HistoryViewModel.Factory::class)
class HistoryViewModel @AssistedInject constructor(
    manager: DeviceConnectionManager,
    @Assisted deviceAddress: String,
    private val history: HistoryRepository,
    private val knownDeviceDao: KnownDeviceDao,
    epochDao: StationEpochDao,
    private val archivedFileDao: ArchivedFileDao,
    private val bucketDao: MetricBucketDao,
    settings: AppSettings,
) : ViewModel() {
    private val repository = manager.controller(deviceAddress)
    private val zone = ZoneId.systemDefault()

    @AssistedFactory
    interface Factory {
        fun create(deviceAddress: String): HistoryViewModel
    }

    private val address = MutableStateFlow<String?>(deviceAddress)
    private val selectedStation = MutableStateFlow<String?>(null)
    private val stations = epochDao.observeForDevice(deviceAddress).map { epochs -> epochs.map { it.station } }
    fun selectStation(station: String) { selectedStation.value = station }

    /** The screen binds its nav key's address once; the station follows from the known-device row. */
    fun bind(address: String) { this.address.value = address }
    private val metric = MutableStateFlow(Metrics.PM25)
    private val range = MutableStateFlow(HistoryRange.DAY)
    private val toS = MutableStateFlow(nowRounded(HistoryRange.DAY, zone))

    /**
     * True until the user pages back: the span then follows "now", so a screen left open across local
     * midnight moves to the new day instead of showing yesterday as today (the initial [toS] was
     * computed once, at construction).
     */
    private val followsNow = MutableStateFlow(true)

    init {
        viewModelScope.launch {
            while (true) {
                delay(60_000)
                if (followsNow.value) toS.value = nowRounded(range.value, zone)
            }
        }
    }

    private val device = combine(knownDeviceDao.observeAll(), address, selectedStation, stations, archivedFileDao.observeAll()) { list, a, selected, epochs, files ->
        list.firstOrNull { it.address == a }?.let { d ->
            d.copy(station = preferredHistoryStation(d.station, epochs, files, selected))
        }
    }

    private val stationQuery = device.map { it?.station }.distinctUntilChanged()

    init {
        viewModelScope.launch {
            stationQuery.collectLatest { station ->
                if (station == null) return@collectLatest
                val latest = latestHistoryDayEnd(archivedFileDao.observeForStation(station).first(), zone)
                val now = nowRounded(range.value, zone)
                toS.value = minOf(latest ?: now, now)
                followsNow.value = toS.value == now
            }
        }
    }

    private val availableMetrics = stationQuery.archiveQueryResults { station ->
        if (station == null) {
            flowOf(emptyList())
        } else {
            combine(
                Metrics.chartable.map { spec ->
                    bucketDao.observeAllDays(spec.id).map { rows -> spec.takeIf { rows.any { it.station == station && it.validRows > 0 } } }
                }
            ) { specs -> specs.filterNotNull() }
        }
    }.onEach { result ->
        val available = result.value.orEmpty()
        if (available.isNotEmpty() && metric.value !in available) metric.value = available.first()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ArchiveQueryResult<String?, List<MetricSpec>>(null, null))

    private val series = combine(device, metric, range, toS) { d, m, r, t -> ArchiveSeriesQuery(d?.station, m, r, HistoryWindow.endingAt(t, r, zone)) }
        .distinctUntilChanged()
        .archiveQueryResults { query ->
            val station = query.station
            if (station == null) flowOf(null) else history.observe(station, query.metric, query.range, query.window)
        }

    private val fileCounts = stationQuery.archiveQueryResults { station ->
        if (station == null) {
            flowOf(HistoryFileCounts())
        } else {
            archivedFileDao.observeForStation(station).map(::countHistoryFiles)
        }
    }

    private val days = stationQuery.archiveQueryResults { station ->
        if (station == null) {
            flowOf(Triple<Int, Long?, Long?>(0, null, null))
        } else {
            combine(bucketDao.observeDayCount(station), bucketDao.observeFirstDay(station), bucketDao.observeLastDay(station)) { n, f, l -> Triple(n, f, l) }
        }
    }

    val uiState: StateFlow<HistoryUiState> = combine(
        device, metric, range, toS, series, fileCounts, days, settings.advancedMode, repository.connectionState, repository.activeTransport, stations, availableMetrics,
    ) { values ->
        val d = values[0] as earth.walkthru.aqsync.data.local.database.KnownDevice?

        @Suppress("UNCHECKED_CAST")
        val countsResult = values[5] as ArchiveQueryResult<String?, HistoryFileCounts>
        val counts = countsResult.forQuery(d?.station) ?: HistoryFileCounts()
        val query = ArchiveSeriesQuery(d?.station, values[1] as MetricSpec, values[2] as HistoryRange, HistoryWindow.endingAt(values[3] as Long, values[2] as HistoryRange, zone))

        @Suppress("UNCHECKED_CAST")
        val result = values[4] as ArchiveQueryResult<ArchiveSeriesQuery, HistorySeries>

        @Suppress("UNCHECKED_CAST")
        val daysResult = values[6] as ArchiveQueryResult<String?, Triple<Int, Long?, Long?>>
        val dd = daysResult.forQuery(d?.station) ?: Triple(0, null, null)

        @Suppress("UNCHECKED_CAST")
        val availableResult = values[11] as ArchiveQueryResult<String?, List<MetricSpec>>
        val available = availableResult.forQuery(d?.station).orEmpty()
        val loading = d?.station != null && (
            result.forQuery(query) == null || countsResult.forQuery(d.station) == null ||
                daysResult.forQuery(d.station) == null || availableResult.forQuery(d.station) == null
            )
        HistoryUiState(
            initializing = loading,
            station = d?.station,
            stations = (values[10] as List<*>).filterIsInstance<String>(),
            deviceName = d?.name,
            metric = values[1] as MetricSpec,
            availableMetrics = available,
            range = values[2] as HistoryRange,
            zone = zone,
            toS = values[3] as Long,
            series = result.forQuery(query),
            filesWithTime = counts.withTime,
            filesTotal = counts.total,
            filesPendingIndex = counts.pending,
            filesIndexFailed = counts.failed,
            filesOtherStation = counts.otherStation,
            filesUntimed = counts.untimed,
            filesEstimated = counts.estimated,
            daysWithData = dd.first,
            firstDayS = dd.second,
            lastDayS = dd.third,
            advancedMode = values[7] as Boolean,
            connection = values[8] as ConnectionState,
            transport = values[9] as TransportKind?,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState(toS = toS.value, zone = zone))

    fun setMetric(m: MetricSpec) { metric.value = m }

    fun setRange(r: HistoryRange) {
        val followedNow = followsNow.value
        range.value = r
        toS.value = if (followedNow) nowRounded(r, zone) else minOf(toS.value, nowRounded(r, zone))
        followsNow.value = toS.value == nowRounded(r, zone)
    }

    fun page(direction: Int) {
        val r = range.value
        val next = HistoryWindow.endingAt(toS.value, r, zone).page(r, direction).toS
        val now = nowRounded(r, zone)
        toS.value = minOf(next, now)
        followsNow.value = toS.value == now
    }

    /** From a calendar cell: show its complete local day. */
    fun openDay(dayStartS: Long) {
        range.value = HistoryRange.DAY
        val now = nowRounded(HistoryRange.DAY, zone)
        toS.value = minOf(HistoryWindow.dayContaining(dayStartS, zone).toS, now)
        followsNow.value = toS.value == now
    }
}
