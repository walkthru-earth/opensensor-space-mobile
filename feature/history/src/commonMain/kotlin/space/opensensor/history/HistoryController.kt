package space.opensensor.history

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
import space.opensensor.archive.ArchiveIndex
import space.opensensor.data.history.ArchiveSeriesQuery
import space.opensensor.data.history.DaySpan
import space.opensensor.data.history.HistoryRange
import space.opensensor.data.history.HistorySeries
import space.opensensor.data.history.HistorySeriesReader
import space.opensensor.data.history.HistoryTiers
import space.opensensor.data.history.HistoryWindow
import space.opensensor.data.history.MetricSpec
import space.opensensor.data.history.Metrics
import space.opensensor.data.history.archiveQueryResults
import kotlin.time.Clock

/** What the History screen shows. Every value comes from the phone archive, so it works offline. */
data class HistoryUiState(
    /** The first archive reads for the selected query have not finished yet. */
    val initializing: Boolean = true,
    val thing: String? = null,
    /** Thing generations to choose from, oldest first. The last one is the current thing. */
    val things: List<String> = emptyList(),
    val metric: MetricSpec = Metrics.default,
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

    /**
     * True while the visible span starts after the first archived day, so "Earlier" can still reach
     * a reading. [DaySpan.firstDayS] is a UTC midnight, the earliest a reading can be.
     */
    val canPageBack: Boolean get() = days.firstDayS?.let { window.fromS > it } ?: false
}

/**
 * State and actions behind [HistoryScreen], free of Android, Hilt and Room (the SettingsController
 * pattern). Android wraps it in a Hilt ViewModel over Room's tiers and index, iOS builds it in
 * `AppRoot` over the receipt index and the in-memory tiers.
 *
 * [currentThing] is the thing History opens on (Android, the known device's thing, iOS, the
 * archived thing with the latest reading). [things] are the generations its chips offer.
 */
class HistoryController(
    private val scope: CoroutineScope,
    private val reader: HistorySeriesReader,
    private val tiers: HistoryTiers,
    private val index: ArchiveIndex,
    currentThing: Flow<String?>,
    things: Flow<List<String>>,
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
    private val clock: Clock = Clock.System,
    tickMs: Long = 60_000,
) {
    private val selectedThing = MutableStateFlow<String?>(null)
    private val metric = MutableStateFlow(Metrics.default)
    private val range = MutableStateFlow(HistoryRange.DAY)
    private val toS = MutableStateFlow(nowRounded(HistoryRange.DAY))
    private val nowToS = MutableStateFlow(toS.value)

    /**
     * True until the user pages back. The span then follows "now", so a screen left open across local
     * midnight moves to the new day instead of showing yesterday as today.
     */
    private val followsNow = MutableStateFlow(true)

    private val thingList = things.distinctUntilChanged()

    private val thing: Flow<String?> = combine(currentThing, thingList, index.observeLatestTimed(), selectedThing) { current, list, latest, selected ->
        preferredHistoryThing(current, list, latest, selected)
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
            thing.collectLatest { s ->
                if (s == null) return@collectLatest
                val latest = latestHistoryDayEnd(index.observeThing(s).first(), zone)
                val now = nowRounded(range.value)
                nowToS.value = now
                toS.value = minOf(latest ?: now, now)
                followsNow.value = toS.value == now
            }
        }
    }

    private val availableMetrics = thing.archiveQueryResults { s ->
        if (s == null) flowOf(emptyList()) else tiers.observeMetricsWithData(s).map { ids -> Metrics.all.filter { it.id in ids } }
    }.onEach { result ->
        val available = result.value.orEmpty()
        if (available.isNotEmpty() && metric.value !in available) metric.value = Metrics.defaultOf(available) ?: available.first()
    }

    private val series = combine(thing, metric, range, toS) { s, m, r, t -> ArchiveSeriesQuery(s, m, r, HistoryWindow.endingAt(t, r, zone)) }
        .distinctUntilChanged()
        .archiveQueryResults { q -> q.thing?.let { reader.observe(it, q.metric, q.range, q.window) } ?: flowOf(null) }

    private val fileCounts = thing.archiveQueryResults { s ->
        if (s == null) flowOf(HistoryFileCounts()) else index.observeThing(s).map(::countHistoryFiles)
    }

    private val days = thing.archiveQueryResults { s -> if (s == null) flowOf(DaySpan()) else tiers.observeDays(s) }

    private data class Selection(val thing: String?, val things: List<String>, val metric: MetricSpec, val range: HistoryRange, val toS: Long, val nowToS: Long)

    private val selection = combine(
        combine(thing, thingList, metric) { s, list, m -> Triple(s, list, m) },
        range,
        toS,
        nowToS,
    ) { (s, list, m), r, t, now -> Selection(s, list, m, r, t, now) }

    val state: StateFlow<HistoryUiState> = combine(selection, series, fileCounts, days, availableMetrics) { sel, seriesResult, countsResult, daysResult, availableResult ->
        val query = ArchiveSeriesQuery(sel.thing, sel.metric, sel.range, HistoryWindow.endingAt(sel.toS, sel.range, zone))
        val result = seriesResult.forQuery(query)
        val counts = countsResult.forQuery(sel.thing)
        val dayResult = daysResult.forQuery(sel.thing)
        val available = availableResult.forQuery(sel.thing)
        HistoryUiState(
            initializing = sel.thing != null && (result == null || counts == null || dayResult == null || available == null),
            thing = sel.thing,
            things = sel.things,
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

    fun selectThing(thing: String) {
        selectedThing.value = thing
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

    /** Back to the span that holds "now", which then follows the clock again. */
    fun showNow() {
        val now = nowRounded(range.value)
        nowToS.value = now
        toS.value = now
        followsNow.value = true
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
