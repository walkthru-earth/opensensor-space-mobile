package earth.walkthru.aqsync.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import earth.walkthru.aqsync.data.DeviceConnectionRepository
import earth.walkthru.aqsync.data.ble.ConnectionState
import earth.walkthru.aqsync.data.ble.TransportKind
import earth.walkthru.aqsync.data.history.HistoryDeriver
import earth.walkthru.aqsync.data.history.HistoryIndexer
import earth.walkthru.aqsync.data.history.HistoryRange
import earth.walkthru.aqsync.data.history.HistoryRepository
import earth.walkthru.aqsync.data.history.HistorySeries
import earth.walkthru.aqsync.data.history.MetricSpec
import earth.walkthru.aqsync.data.history.Metrics
import earth.walkthru.aqsync.data.local.database.ArchivedFileDao
import earth.walkthru.aqsync.data.local.database.KnownDeviceDao
import earth.walkthru.aqsync.data.local.database.MetricBucketDao
import earth.walkthru.aqsync.data.settings.AppSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HistoryUiState(
    val station: String? = null,
    val deviceName: String? = null,
    val metric: MetricSpec = Metrics.PM25,
    val range: HistoryRange = HistoryRange.DAY,
    /** End of the visible span (epoch seconds); "now" rounded up unless the user paged back. */
    val toS: Long = 0,
    val series: HistorySeries? = null,
    /** Verified telemetry files with UTC rows / all verified files of the station. */
    val filesWithTime: Int = 0,
    val filesTotal: Int = 0,
    val filesUnindexed: Int = 0,
    /** Files placed on the timeline by a §4.6 estimate. */
    val filesEstimated: Int = 0,
    val daysWithData: Int = 0,
    val firstDayS: Long? = null,
    val lastDayS: Long? = null,
    val advancedMode: Boolean = false,
    val connection: ConnectionState = ConnectionState.Disconnected,
    val transport: TransportKind? = null,
) {
    val canPageForward: Boolean get() = toS < nowRounded(range)
}

/**
 * The end of the span that contains "now": the next **local midnight**. Every range is then whole
 * local days — a Day is a calendar day (the ring's dial has midnight at the top), a Week seven of
 * them ending tonight, and so on. The buckets underneath stay UTC-aligned.
 */
fun nowRounded(@Suppress("UNUSED_PARAMETER") range: HistoryRange, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Long =
    java.time.LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toEpochSecond()

/**
 * History works from the archive alone — no session needed — but shows the link chip when one exists.
 * The station is looked up from the known device; a logger that was never connected has no station
 * and therefore no history yet.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val repository: DeviceConnectionRepository,
    private val history: HistoryRepository,
    private val knownDeviceDao: KnownDeviceDao,
    private val archivedFileDao: ArchivedFileDao,
    private val bucketDao: MetricBucketDao,
    settings: AppSettings,
    historyIndexer: HistoryIndexer,
    historyDeriver: HistoryDeriver,
) : ViewModel() {

    private val address = MutableStateFlow<String?>(null)

    /** The screen binds its nav key's address once; the station follows from the known-device row. */
    fun bind(address: String) { this.address.value = address }
    private val metric = MutableStateFlow(Metrics.PM25)
    private val range = MutableStateFlow(HistoryRange.DAY)
    private val toS = MutableStateFlow(nowRounded(HistoryRange.DAY))

    /**
     * True until the user pages back: the span then follows "now", so a screen left open across local
     * midnight moves to the new day instead of showing yesterday as today (the initial [toS] was
     * computed once, at construction).
     */
    private val followsNow = MutableStateFlow(true)

    init {
        // Same idempotent housekeeping as Files: index anything old, derive any stale day, then the flows below re-emit.
        viewModelScope.launch {
            runCatching { historyIndexer.backfillAll() }
            runCatching { historyDeriver.deriveStale() }
        }
        viewModelScope.launch {
            while (true) {
                delay(60_000)
                if (followsNow.value) toS.value = nowRounded(range.value)
            }
        }
    }

    private val device = combine(knownDeviceDao.observeAll(), address) { list, a -> list.firstOrNull { it.address == a } }

    private val series = combine(device, metric, range, toS) { d, m, r, t -> Triple(d?.station, m, r) to t }
        .flatMapLatest { (key, t) ->
            val (station, m, r) = key
            if (station == null) flowOf(null) else history.observe(station, m, r, t)
        }

    private data class Counts(val withTime: Int, val total: Int, val unindexed: Int, val estimated: Int)

    private val fileCounts = device.flatMapLatest { d ->
        val station = d?.station ?: return@flatMapLatest flowOf(Counts(0, 0, 0, 0))
        archivedFileDao.observeForStation(station).map { files ->
            Counts(
                withTime = files.count { it.indexed && it.hasUtc && (it.purpose == null || it.purpose == "telemetry") },
                total = files.size,
                unindexed = files.count { !it.indexed },
                estimated = files.count { it.indexed && it.hasEstimatedUtc },
            )
        }
    }

    private val days = device.flatMapLatest { d ->
        val station = d?.station ?: return@flatMapLatest flowOf(Triple(0, null, null))
        combine(bucketDao.observeDayCount(station), bucketDao.observeFirstDay(station), bucketDao.observeLastDay(station)) { n, f, l -> Triple(n, f, l) }
    }

    val uiState: StateFlow<HistoryUiState> = combine(
        device, metric, range, toS, series, fileCounts, days, settings.advancedMode, repository.connectionState, repository.activeTransport,
    ) { values ->
        val d = values[0] as earth.walkthru.aqsync.data.local.database.KnownDevice?
        val counts = values[5] as Counts

        @Suppress("UNCHECKED_CAST")
        val dd = values[6] as Triple<Int, Long?, Long?>
        HistoryUiState(
            station = d?.station,
            deviceName = d?.name,
            metric = values[1] as MetricSpec,
            range = values[2] as HistoryRange,
            toS = values[3] as Long,
            series = values[4] as HistorySeries?,
            filesWithTime = counts.withTime,
            filesTotal = counts.total,
            filesUnindexed = counts.unindexed,
            filesEstimated = counts.estimated,
            daysWithData = dd.first,
            firstDayS = dd.second,
            lastDayS = dd.third,
            advancedMode = values[7] as Boolean,
            connection = values[8] as ConnectionState,
            transport = values[9] as TransportKind?,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState(toS = toS.value))

    fun setMetric(m: MetricSpec) { metric.value = m }

    fun setRange(r: HistoryRange) {
        range.value = r
        toS.value = nowRounded(r)
        followsNow.value = true
    }

    fun page(direction: Int) {
        val r = range.value
        val next = toS.value + direction * r.seconds
        val now = nowRounded(r)
        toS.value = minOf(next, now)
        followsNow.value = toS.value == now
    }

    /** From a calendar cell: show that UTC day (ending at its midnight). */
    fun openDay(dayStartS: Long) {
        range.value = HistoryRange.DAY
        val now = nowRounded(HistoryRange.DAY)
        toS.value = minOf(dayStartS + 86_400, now)
        followsNow.value = toS.value == now
    }

    /** Jump so the latest data is in view (the last day with data, or now). */
    fun jumpToLatest() {
        toS.value = nowRounded(range.value)
        followsNow.value = true
    }
}
