package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.archive.ArchiveIndex
import earth.walkthru.aqsync.archive.ArchiveRecord
import earth.walkthru.aqsync.archive.ArchiveStore
import earth.walkthru.aqsync.data.history.parquet.ParquetFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [HistoryTiers] computed straight from the archive, for a phone without a derived tier store (iOS
 * until Room KMP lands). The same rule as Android's `HistoryDeriver`: one UTC station-day at a time,
 * from the verified, indexed telemetry files on that day's timeline, through the shared [Aggregator],
 * keeping the buckets that start inside the day.
 *
 * Each day is cached in memory by its source signature (file name, CRC and estimate), so a new file
 * rebuilds only its day and an unchanged day costs one string comparison. Nothing is written.
 */
class ArchiveHistoryTiers(
    private val store: ArchiveStore,
    private val index: ArchiveIndex,
    private val io: CoroutineDispatcher = Dispatchers.Default,
) : HistoryTiers {
    private class Day(val signature: String, val buckets: Map<Resolution, Map<String, List<TierBucket>>>)

    /** What one station's tiers depend on, each UTC day's source files and the boots' time anchors. */
    private data class Sources(val days: Map<Long, List<ArchiveRecord>>, val anchors: Map<String, TimeEstimate.Anchor>)

    private val mutex = Mutex()
    private val cache = HashMap<Pair<String, Long>, Day>()

    override fun observe(station: String, metricId: String, tier: Resolution, fromS: Long, toS: Long): Flow<List<TierBucket>> =
        days(station).map { days ->
            days.values.flatMap { it.buckets[tier]?.get(metricId).orEmpty() }
                .filter { it.startEpochS in fromS until toS }
                .sortedBy { it.startEpochS }
        }.distinctUntilChanged()

    override fun observeDays(station: String): Flow<DaySpan> = days(station).map { days ->
        val withData = days.filterValues { day -> day.buckets[Resolution.DAY].orEmpty().isNotEmpty() }.keys
        DaySpan(withData.size, withData.minOrNull()?.times(DAY_S), withData.maxOrNull()?.times(DAY_S))
    }.distinctUntilChanged()

    override fun observeMetricsWithData(station: String): Flow<Set<String>> = days(station).map { days ->
        days.values.flatMap { day -> day.buckets[Resolution.DAY].orEmpty().filterValues { list -> list.any { it.validRows > 0 } }.keys }.toSet()
    }.distinctUntilChanged()

    /** The station's derived days keyed by UTC epoch day, rebuilt only where the sources changed. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun days(station: String): Flow<Map<Long, Day>> = index.observeStation(station)
        .map { records -> sources(station, records) }
        .distinctUntilChanged()
        .mapLatest { sources -> build(station, sources) }
        .flowOn(io)

    /** UTC epoch day to its source files, and the boots' anchors, from the index alone. */
    private fun sources(station: String, records: List<ArchiveRecord>): Sources {
        val own = records.filter { it.station == station }
        val days = StationDays.of(own)[station].orEmpty().associateWith { day ->
            val from = day * StationDays.DAY_NS
            own.filter { it.indexed && it.onTimeline(from, from + StationDays.DAY_NS) }.sortedWith(ArchiveRecord.TIMELINE_ORDER)
        }
        return Sources(days, TimeEstimate.anchors(own.filter { it.indexed }.map { it.info() }))
    }

    private suspend fun build(station: String, sources: Sources): Map<Long, Day> = mutex.withLock {
        val anchors = sources.anchors
        val out = HashMap<Long, Day>()
        for ((day, files) in sources.days) {
            val sig = signature(files)
            val key = station to day
            val cached = cache[key]
            out[day] = if (cached?.signature == sig) cached else buildDay(station, day, files, anchors, sig).also { cache[key] = it }
        }
        // Days that lost every source drop out of the cache too.
        cache.keys.removeAll { it.first == station && it.second !in sources.days }
        out
    }

    private fun buildDay(station: String, epochDay: Long, files: List<ArchiveRecord>, anchors: Map<String, TimeEstimate.Anchor>, signature: String): Day {
        val dayStartS = epochDay * DAY_S
        val agg = Aggregator(station)
        for (src in files) {
            val pf = try {
                ParquetFile.open(store.reader(src.key), src.name)
            } catch (e: Exception) {
                continue
            }
            try {
                // Estimated time only for files with no UTC of their own whose boot has an anchor.
                agg.add(pf, if (src.hasEstimatedUtc) anchors[src.bootId] else null)
            } catch (e: Exception) {
                continue
            }
        }
        // Keep the buckets that start inside this day. A file across midnight gives its other rows to
        // the neighbouring day, whose source set holds it too.
        val buckets = agg.buckets().filter { it.startEpochS >= dayStartS && it.startEpochS < dayStartS + DAY_S }
        val byTier = buckets.groupBy { it.resolution }.mapValues { (_, list) ->
            val perMetric = HashMap<String, MutableList<TierBucket>>()
            for (b in list) for ((id, t) in b.tierBuckets()) perMetric.getOrPut(id) { ArrayList() } += t
            perMetric
        }
        return Day(signature, byTier)
    }

    companion object {
        private const val DAY_S = 86_400L

        /** The identity of one day's sources, sorted `name:crc32[:est<anchor>:<first>]` lines (the derive signature without the hash). */
        fun signature(files: List<ArchiveRecord>): String = files
            .map { "${it.name}:${it.crc32}" + (if (it.hasEstimatedUtc) ":est${it.estAnchorFile}:${it.estFirstUtcNs}" else "") }
            .sorted()
            .joinToString("\n")
    }
}
