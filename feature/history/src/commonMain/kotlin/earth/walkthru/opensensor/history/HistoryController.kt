package earth.walkthru.opensensor.history

import earth.walkthru.aqsync.archive.ArchiveIndex
import earth.walkthru.aqsync.data.history.ArchiveSeriesQuery
import earth.walkthru.aqsync.data.history.DaySpan
import earth.walkthru.aqsync.data.history.HistoryRange
import earth.walkthru.aqsync.data.history.HistorySeries
import earth.walkthru.aqsync.data.history.HistorySeriesReader
import earth.walkthru.aqsync.data.history.HistoryTiers
import earth.walkthru.aqsync.data.history.HistoryWindow
import earth.walkthru.aqsync.data.history.MetricSpec
import earth.walkthru.aqsync.data.history.Metrics
import earth.walkthru.aqsync.data.history.archiveQueryResults
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
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
import kotlinx.datetime.TimeZone
import kotlin.time.Clock

/** What the History screen shows. Every value comes from the phone archive, so it works offline. */
data class HistoryUiState(
    /** The first archive reads for the selected query have not finished yet. */
    val initializing: Boolean = true,
    val station: String? = null,
    /** Station generations to choose from, oldest first. The last one is the current station. */
    val stations: List<String> = emptyList(),
    val metric: MetricSpec = Metrics.PM25_CORRECTED,
    val availableMetrics: List<MetricSpec> = emptyList(),
    val range: HistoryRange = HistoryRange.DAY,
    val zone: TimeZone = TimeZone.UTC,
    /** End of the visible span (epoch seconds), the next local midnight unless the user paged back. */
    val toS: Long = 0,
    /** The end of the span holding "now", for [canPageForward] and the day ring's now tick. */
    val nowToS: Long = 0,
    val series: HistorySeries? = null,
    val files: HistoryFileCounts = HistoryFileCounts(),
    val days: DaySpan = DaySpan(),
) {
    val window: HistoryWindow get() = HistoryWindow.endingAt(toS, range, zone)
    val canPageForward: Boolean get() = toS < nowToS
}

/**
 * State and actions behind [HistoryScreen], free of Android, Hilt and Room (the SettingsController
 * pattern). Android wraps it in a Hilt ViewModel over Room's tiers and index, iOS builds it in
 * `AppRoot` over the receipt index and the in-memory tiers.
 *
 * [currentStation] is the station History opens on (Android, the known device's station, iOS, the
 * archived station with the latest reading). [stations] are the generations its chips offer.
 */
class HistoryController(
    private val scope: CoroutineScope,
    private val reader: HistorySeriesReader,
    private val tiers: HistoryTiers,
    private val index: ArchiveIndex,
    currentStation: Flow<String?>,
    stations: Flow<List<String>>,
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
    private val clock: Clock = Clock.System,
    tickMs: Long = 60_000,
) {
    private val selectedStation = MutableStateFlow<String?>(null)
    private val metric = MutableStateFlow(Metrics.PM25_CORRECTED)
    private val range = MutableStateFlow(HistoryRange.DAY)
    private val toS = MutableStateFlow(nowRounded(HistoryRange.DAY))
    private val nowToS = MutableStateFlow(toS.value)

    /**
     * True until the user pages back. The span then follows "now", so a screen left open across local
     * midnight moves to the new day instead of showing yesterday as today.
     */
    private val followsNow = MutableStateFlow(true)

    private val stationList = stations.distinctUntilChanged()

    private val station: Flow<String?> = combine(currentStation, stationList, index.observeLatestTimed(), selectedStation) { current, list, latest, selected ->
        preferredHistoryStation(current, list, latest, selected)
    }.distinctUntilChanged()

    init {
        scope.launch {
            while (true) {
                delay(tickMs)
                nowToS.value = nowRounded(range.value)
                if (followsNow.value) toS.value = nowToS.value
            }
        }
        scope.launch {
            station.collectLatest { s ->
                if (s == null) return@collectLatest
                val latest = latestHistoryDayEnd(index.observeStation(s).first(), zone)
                val now = nowRounded(range.value)
                nowToS.value = now
                toS.value = minOf(latest ?: now, now)
                followsNow.value = toS.value == now
            }
        }
    }

    private val availableMetrics = station.archiveQueryResults { s ->
        if (s == null) flowOf(emptyList()) else tiers.observeMetricsWithData(s).map { ids -> Metrics.chartable.filter { it.id in ids } }
    }.onEach { result ->
        val available = result.value.orEmpty()
        if (available.isNotEmpty() && metric.value !in available) metric.value = available.first()
    }

    private val series = combine(station, metric, range, toS) { s, m, r, t -> ArchiveSeriesQuery(s, m, r, HistoryWindow.endingAt(t, r, zone)) }
        .distinctUntilChanged()
        .archiveQueryResults { q -> q.station?.let { reader.observe(it, q.metric, q.range, q.window) } ?: flowOf(null) }

    private val fileCounts = station.archiveQueryResults { s ->
        if (s == null) flowOf(HistoryFileCounts()) else index.observeStation(s).map(::countHistoryFiles)
    }

    private val days = station.archiveQueryResults { s -> if (s == null) flowOf(DaySpan()) else tiers.observeDays(s) }

    private data class Selection(val station: String?, val stations: List<String>, val metric: MetricSpec, val range: HistoryRange, val toS: Long, val nowToS: Long)

    private val selection = combine(
        combine(station, stationList, metric) { s, list, m -> Triple(s, list, m) },
        range,
        toS,
        nowToS,
    ) { (s, list, m), r, t, now -> Selection(s, list, m, r, t, now) }

    val state: StateFlow<HistoryUiState> = combine(selection, series, fileCounts, days, availableMetrics) { sel, seriesResult, countsResult, daysResult, availableResult ->
        val query = ArchiveSeriesQuery(sel.station, sel.metric, sel.range, HistoryWindow.endingAt(sel.toS, sel.range, zone))
        val result = seriesResult.forQuery(query)
        val counts = countsResult.forQuery(sel.station)
        val dayResult = daysResult.forQuery(sel.station)
        val available = availableResult.forQuery(sel.station)
        HistoryUiState(
            initializing = sel.station != null && (result == null || counts == null || dayResult == null || available == null),
            station = sel.station,
            stations = sel.stations,
            metric = sel.metric,
            availableMetrics = available.orEmpty(),
            range = sel.range,
            zone = zone,
            toS = sel.toS,
            nowToS = sel.nowToS,
            series = result,
            files = counts ?: HistoryFileCounts(),
            days = dayResult ?: DaySpan(),
        )
    }.stateIn(scope, SharingStarted.Eagerly, HistoryUiState(zone = zone, toS = toS.value, nowToS = nowToS.value))

    fun selectStation(station: String) {
        selectedStation.value = station
    }

    fun setMetric(m: MetricSpec) {
        metric.value = m
    }

    fun setRange(r: HistoryRange) {
        val followedNow = followsNow.value
        val now = nowRounded(r)
        range.value = r
        nowToS.value = now
        toS.value = if (followedNow) now else minOf(toS.value, now)
        followsNow.value = toS.value == now
    }

    fun page(direction: Int) {
        val r = range.value
        val next = HistoryWindow.endingAt(toS.value, r, zone).page(r, direction).toS
        val now = nowRounded(r)
        nowToS.value = now
        toS.value = minOf(next, now)
        followsNow.value = toS.value == now
    }

    /** From a calendar cell, show its complete local day. */
    fun openDay(dayStartS: Long) {
        range.value = HistoryRange.DAY
        val now = nowRounded(HistoryRange.DAY)
        nowToS.value = now
        toS.value = minOf(HistoryWindow.dayContaining(dayStartS, zone).toS, now)
        followsNow.value = toS.value == now
    }

    /** The end of the span that contains "now", the next local midnight. */
    private fun nowRounded(r: HistoryRange): Long = HistoryWindow.current(r, zone, clock).toS

    /** The current time in epoch seconds, for the day ring's now tick. */
    fun nowS(): Long = clock.now().epochSeconds
}
