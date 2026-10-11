package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.archive.ArchiveIndex
import earth.walkthru.aqsync.archive.ArchiveRecord
import earth.walkthru.aqsync.archive.ArchiveStore
import earth.walkthru.aqsync.archive.ClockAnchorStore
import earth.walkthru.aqsync.archive.EmptyClockAnchorStore
import earth.walkthru.aqsync.data.history.parquet.ParquetFile
import earth.walkthru.aqsync.storage.ByteBudgetLru
import earth.walkthru.aqsync.storage.MemoryPressure
import earth.walkthru.aqsync.storage.MemoryTrimmable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * [HistoryTiers] computed straight from the archive, for a phone without a derived tier store (iOS
 * until Room KMP lands). The same rule as Android's `HistoryDeriver`: one UTC station-day at a time,
 * from the verified, indexed telemetry files on that day's timeline, through the shared [Aggregator],
 * keeping the buckets that start inside the day.
 *
 * Each day is cached in memory by its source signature (file name, CRC and estimate), so a new file
 * rebuilds only its day and an unchanged day costs one string comparison. The cache is a least
 * recently used map within [budgetBytes] of estimated size (#61), and [trim] gives memory back on
 * [MemoryPressure]. Behind it the [tierStore] keeps each day on disk under the same signature, so a
 * new process or an evicted day reads its tiers without decoding Parquet (#63). Only a day missing
 * from both is decoded, one [DayPass] over its files. Nothing is written to the archive.
 *
 * With a [shareScope] the three observers of one station share one upstream, so the index is mapped
 * and the days are checked once per change instead of once per collector.
 */
class ArchiveHistoryTiers(
    private val store: ArchiveStore,
    private val index: ArchiveIndex,
    private val io: CoroutineDispatcher = Dispatchers.Default,
    private val clockAnchors: ClockAnchorStore = EmptyClockAnchorStore,
    budgetBytes: Long = DEFAULT_BUDGET_BYTES,
    private val tierStore: TierStore = TierStore.NONE,
    private val shareScope: CoroutineScope? = null,
) : HistoryTiers,
    MemoryTrimmable {
    internal class Day(val signature: String, val buckets: DayTiers)

    /** What one station's tiers depend on, each UTC day's source files and the boots' time anchors. */
    private data class Sources(val days: Map<Long, List<ArchiveRecord>>, val anchors: Map<String, TimeEstimate.Anchor>)

    private val mutex = Mutex()
    private val cache = ByteBudgetLru<Pair<String, Long>, Day>(budgetBytes) { key, day -> estimatedBytes(key, day) }

    /** Estimated bytes the cache keeps alive now, for tests and logs. */
    suspend fun cachedBytes(): Long = mutex.withLock { cache.sizeBytes }

    /** How many days the cache holds now. */
    suspend fun cachedDays(): Int = mutex.withLock { cache.size }

    override suspend fun trim(level: MemoryPressure.Level) = mutex.withLock { cache.trim(level) }

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

    /** Decodes this instance ran, for tests and logs: one per day built from the archive. */
    var decodedDays: Int = 0
        private set

    /** One shared upstream per station, an immutable map swapped whole. */
    @OptIn(ExperimentalAtomicApi::class)
    private val shared = AtomicReference<Map<String, Flow<Map<Long, Day>>>>(emptyMap())

    /** The station's derived days keyed by UTC epoch day, rebuilt only where the sources changed. */
    @OptIn(ExperimentalAtomicApi::class)
    private fun days(station: String): Flow<Map<Long, Day>> {
        val scope = shareScope ?: return upstream(station)
        // Lazily per station, kept for the process like the station itself. Idle for 5 s it stops collecting the index.
        while (true) {
            val now = shared.load()
            now[station]?.let { return it }
            val flow = upstream(station).shareIn(scope, SharingStarted.WhileSubscribed(5_000), replay = 1)
            if (shared.compareAndSet(now, now + (station to flow))) return flow
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun upstream(station: String): Flow<Map<Long, Day>> = index.observeStation(station)
        .map { records -> sources(station, records) }
        .distinctUntilChanged()
        .mapLatest { sources -> build(station, sources) }
        .flowOn(io)

    /** UTC epoch day to its source files, and the boots' anchors, from the index alone. */
    private suspend fun sources(station: String, records: List<ArchiveRecord>): Sources {
        val own = records.filter { it.station == station && it.indexed }
        // One pass over the station for every day, in the timeline order the pass reads them (#63).
        val days = StationDays.sourcesByDay(own) { it }
        return Sources(days, TimeEstimate.anchors(own.map { it.info() }, clockAnchors.anchors(station)))
    }

    private suspend fun build(station: String, sources: Sources): Map<Long, Day> = mutex.withLock {
        val anchors = sources.anchors
        val out = HashMap<Long, Day>()
        for ((day, files) in sources.days) {
            val sig = signature(files)
            val key = station to day
            val cached = cache[key]
            out[day] = if (cached?.signature == sig) {
                cached
            } else {
                val saved = tierStore.load(station, day, sig)
                val built = if (saved != null) Day(sig, saved) else buildDay(station, day, files, anchors, sig).also { tierStore.save(station, day, sig, it.buckets) }
                built.also { cache[key] = it }
            }
        }
        // Days that lost every source drop out of the cache and the store too.
        cache.removeAll { it.first == station && it.second !in sources.days }
        tierStore.retain(station, sources.days.keys)
        out
    }

    private fun buildDay(station: String, epochDay: Long, files: List<ArchiveRecord>, anchors: Map<String, TimeEstimate.Anchor>, signature: String): Day {
        decodedDays++
        // One file at a time, closed before the next opens, so 32-bit phones keep address space (#61).
        // A file that does not open or that the pass refuses is left out, the others still count.
        val sources = files.map { src -> DailyParquetSource(src) { ParquetFile.open(store.reader(src.key), src.name) } }
        // The pass keeps the buckets that start inside this day. A file across midnight gives its other
        // rows to the neighbouring day, whose source set holds it too.
        val buckets = DayPass.run(station, epochDay, sources, sink = null, anchors = anchors, nowEpochS = 0).buckets
        val byTier = buckets.groupBy { it.resolution }.mapValues { (_, list) ->
            val perMetric = HashMap<String, MutableList<TierBucket>>()
            for (b in list) for ((id, t) in b.tierBuckets()) perMetric.getOrPut(id) { ArrayList() } += t
            perMetric
        }
        return Day(signature, byTier)
    }

    companion object {
        private const val DAY_S = 86_400L

        /** The cache budget of #61. A day of one station with every tier and metric is about 100 KB. */
        const val DEFAULT_BUDGET_BYTES: Long = 8L * 1024 * 1024

        // Rough JVM and Kotlin/Native object sizes: a TierBucket is a header and seven fields, each
        // list or map entry adds a reference and its node.
        private const val BUCKET_BYTES = 56L
        private const val ENTRY_BYTES = 48L
        private const val DAY_BYTES = 96L

        /** What one cached day keeps alive, estimated from its bucket and entry counts. */
        internal fun estimatedBytes(key: Pair<String, Long>, day: Day): Long {
            var bytes = DAY_BYTES + 2L * (key.first.length + day.signature.length)
            for ((_, perMetric) in day.buckets) {
                bytes += ENTRY_BYTES
                for ((metric, list) in perMetric) bytes += ENTRY_BYTES + 2L * metric.length + list.size * (BUCKET_BYTES + 8)
            }
            return bytes
        }

        /** The identity of one day's sources, sorted `name:crc32[:est<anchor>:<first>]` lines (the derive signature without the hash). */
        fun signature(files: List<ArchiveRecord>): String = files
            .map { "${it.name}:${it.crc32}" + (if (it.hasEstimatedUtc) ":est${it.estAnchorFile}:${it.estFirstUtcNs}" else "") }
            .sorted()
            .joinToString("\n")
    }
}
