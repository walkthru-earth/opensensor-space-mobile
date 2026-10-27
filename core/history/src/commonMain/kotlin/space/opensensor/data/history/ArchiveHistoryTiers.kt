package space.opensensor.data.history

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import space.opensensor.archive.ArchiveIndex
import space.opensensor.archive.ArchiveRecord
import space.opensensor.archive.ArchiveStore
import space.opensensor.archive.ClockAnchorStore
import space.opensensor.archive.EmptyClockAnchorStore
import space.opensensor.data.history.parquet.ParquetFile
import space.opensensor.storage.ByteBudgetLru
import space.opensensor.storage.MemoryPressure
import space.opensensor.storage.MemoryTrimmable
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * [HistoryTiers] computed straight from the archive, for a phone without a derived tier store (iOS
 * until Room KMP lands). The same rule as Android's `HistoryDeriver`: one UTC thing-day at a time,
 * from the verified, indexed telemetry files on that day's timeline, through the shared [Aggregator],
 * keeping the buckets that start inside the day.
 *
 * Each day is cached in memory by its source signature (file name, CRC, estimate and the template the
 * file resolves to), so a new file or a newly served template rebuilds only its days and an unchanged
 * day costs one string comparison. A thing's days are checked again when [rederive] finds that a
 * template change touched one of its files, settled and with a cooldown. The cache is a least
 * recently used map within [budgetBytes] of estimated size (#61), and [trim] gives memory back on
 * [MemoryPressure]. Behind it the [tierStore] keeps each day on disk under the same signature, so a
 * new process or an evicted day reads its tiers without decoding Parquet (#63). Only a day missing
 * from both is decoded, one [DayPass] over its files. Nothing is written to the archive.
 *
 * A day that lost every source while a record waits for its index keeps its saved tiers in History
 * until the record indexes again, the shared rule of [ThingDays.kept] that Android's derive follows.
 *
 * With a [shareScope] the three observers of one thing share one upstream, so the index is mapped
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
    /** iOS derives the daily file and saves its tiers together before this reader loads them. */
    private val beforeBuild: suspend (String, List<ArchiveRecord>, Map<String, TimeEstimate.Anchor>) -> Unit = { _, _, _ -> },
    /** Runs after the prepared tiers were loaded, for deferred derived quota enforcement on iOS. */
    private val afterBuild: suspend () -> Unit = {},
    /** Resolves each file's board template, so its metrics read with that template's gates and units. */
    private val templates: FileTemplates = FileTemplates.SERVED,
    /** The shared template gate, null where templates never change while this reader lives. */
    private val rederive: TemplateRederive? = null,
) : HistoryTiers,
    MemoryTrimmable {
    internal class Day(val signature: String, val buckets: DayTiers, val cacheable: Boolean = true)

    /** What one thing's tiers depend on, each UTC day's source files and the boots' time anchors. */
    private data class Sources(
        val records: List<ArchiveRecord>,
        val days: Map<Long, List<ArchiveRecord>>,
        /** Each day's [signature], so a template change that alters none of them builds nothing. */
        val signatures: Map<Long, String>,
        val anchors: Map<String, TimeEstimate.Anchor>,
        /** Days kept in the store while a record waits for its index, see [ThingDays.held]. */
        val held: ThingDays.Held = ThingDays.Held.NONE,
    )

    /** No bucket references escape into the shared replay, so trimming the LRU releases whole days. */
    private data class Summary(val signature: String, val withData: Boolean, val metrics: Set<String>)
    private data class Thing(val sources: Sources, val days: Map<Long, Summary>)

    private val mutex = Mutex()
    private val cache = ByteBudgetLru<Pair<String, Long>, Day>(budgetBytes) { key, day -> estimatedBytes(key, day) }

    /** Estimated bytes the cache keeps alive now, for tests and logs. */
    suspend fun cachedBytes(): Long = mutex.withLock { cache.sizeBytes }

    /** How many days the cache holds now. */
    suspend fun cachedDays(): Int = mutex.withLock { cache.size }

    override suspend fun trim(level: MemoryPressure.Level) = mutex.withLock { cache.trim(level) }

    override fun observe(thing: String, metricId: String, tier: Resolution, fromS: Long, toS: Long): Flow<List<TierBucket>> =
        days(thing).map { state ->
            mutex.withLock {
                state.days.flatMap { (day, summary) ->
                    if (day * DAY_S >= toS || (day + 1) * DAY_S <= fromS) {
                        emptyList()
                    } else {
                        loadDay(thing, day, state.sources.days[day], state.sources.anchors, summary.signature)
                            .buckets[tier]?.get(metricId).orEmpty().filter { it.startEpochS in fromS until toS }
                    }
                }.sortedBy { it.startEpochS }
            }
        }.distinctUntilChanged().flowOn(io)

    override fun observeDays(thing: String): Flow<DaySpan> = days(thing).map { state ->
        val withData = state.days.filterValues { it.withData }.keys
        DaySpan(withData.size, withData.minOrNull()?.times(DAY_S), withData.maxOrNull()?.times(DAY_S))
    }.distinctUntilChanged()

    override fun observeMetricsWithData(thing: String): Flow<Set<String>> = days(thing).map { state ->
        state.days.values.flatMap { it.metrics }.toSet()
    }.distinctUntilChanged()

    /** Decodes this instance ran, for tests and logs: one per day built from the archive. */
    var decodedDays: Int = 0
        private set

    /** One shared upstream per thing, an immutable map swapped whole. */
    @OptIn(ExperimentalAtomicApi::class)
    private val shared = AtomicReference<Map<String, Lazy<Flow<Thing>>>>(emptyMap())

    /** The thing's derived days keyed by UTC epoch day, rebuilt only where the sources changed. */
    @OptIn(ExperimentalAtomicApi::class)
    private fun days(thing: String): Flow<Thing> {
        val scope = shareScope ?: return upstream(thing)
        // Lazily per thing, kept for the process like the thing itself. Idle for 5 s it stops collecting the index.
        while (true) {
            val now = shared.load()
            now[thing]?.let { return it.value }
            // Only the CAS winner creates a sharing coroutine, concurrent first observers leave no idle jobs.
            val flow = lazy { upstream(thing).shareIn(scope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), replay = 1) }
            if (shared.compareAndSet(now, now + (thing to flow))) return flow.value
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun upstream(thing: String): Flow<Thing> = (rederive?.let { gate -> combine(index.observeThing(thing), gate.generation(thing)) { records, _ -> records } } ?: index.observeThing(thing))
        .map { records -> sources(thing, records) }
        .distinctUntilChanged()
        .mapLatest { sources -> build(thing, sources) }
        .flowOn(io)

    /** UTC epoch day to its source files, and the boots' anchors, from the index alone. */
    private suspend fun sources(thing: String, records: List<ArchiveRecord>): Sources {
        val own = records.filter { it.thing == thing && it.isIndexed }
        // One pass over the thing for every day, in the timeline order the pass reads them (#63).
        val days = ThingDays.sourcesByDay(own) { it }
        val held = ThingDays.held(records.filter { it.thing == thing })
        return Sources(own, days, days.mapValues { (_, files) -> signature(files, templates) }, TimeEstimate.anchors(own.map { it.info() }, clockAnchors.anchors(thing)), held)
    }

    private suspend fun build(thing: String, sources: Sources): Thing {
        beforeBuild(thing, sources.records, sources.anchors)
        val built = mutex.withLock {
            val out = HashMap<Long, Summary>()
            for ((day, files) in sources.days) {
                val sig = sources.signatures.getValue(day)
                out[day] = summary(sig, loadDay(thing, day, files, sources.anchors, sig))
            }
            // A day that lost every source while a waiting record holds it shows its saved tiers (ThingDays.kept).
            val kept = if (sources.held == ThingDays.Held.NONE) emptySet() else ThingDays.kept(tierStore.savedDays(thing), sources.days.keys, sources.held)
            for (day in kept) {
                val saved = tierStore.loadSaved(thing, day) ?: continue
                val tiers = Day(saved.signature, saved.tiers)
                cache[thing to day] = tiers
                out[day] = summary(saved.signature, tiers)
            }
            // Other days that lost every source drop out of the cache and the store too.
            cache.removeAll { it.first == thing && it.second !in sources.days && it.second !in out }
            if (!sources.held.all) tierStore.retain(thing, sources.days.keys + sources.held.days)
            Thing(sources, out)
        }
        afterBuild()
        return built
    }

    private fun summary(sig: String, day: Day): Summary {
        val buckets = day.buckets[Resolution.DAY].orEmpty()
        return Summary(sig, buckets.isNotEmpty(), buckets.filterValues { list -> list.any { it.validRows > 0 } }.keys.toSet())
    }

    /**
     * Called under [mutex], keeps the only full-day references in the budgeted LRU. A held day without
     * [files] reads only its saved tiers, and is empty when the store lost them meanwhile.
     */
    private suspend fun loadDay(thing: String, day: Long, files: List<ArchiveRecord>?, anchors: Map<String, TimeEstimate.Anchor>, sig: String): Day {
        val key = thing to day
        val cached = cache[key]
        if (cached?.signature == sig) return cached
        val saved = tierStore.load(thing, day, sig)
        val built = if (saved != null) {
            Day(sig, saved)
        } else if (files == null) {
            return Day(sig, emptyMap(), cacheable = false)
        } else {
            buildDay(thing, day, files, anchors, sig).also {
                if (it.cacheable) tierStore.save(thing, day, sig, it.buckets)
            }
        }
        if (built.cacheable) cache[key] = built
        return built
    }

    private fun buildDay(thing: String, epochDay: Long, files: List<ArchiveRecord>, anchors: Map<String, TimeEstimate.Anchor>, signature: String): Day {
        decodedDays++
        // One file at a time, closed before the next opens, so 32-bit phones keep address space (#61).
        // A file that does not open or that the pass refuses is left out, the others still count.
        val sources = files.map { src -> DailyParquetSource(src) { ParquetFile.open(store.reader(src.key), src.name) } }
        // The pass keeps the buckets that start inside this day. A file across midnight gives its other
        // rows to the neighbouring day, whose source set holds it too.
        val pass = DayPass.run(thing, epochDay, sources, sink = null, anchors = anchors, nowEpochS = 0, templates = templates)
        return Day(signature, TierStore.fromBuckets(pass.buckets), cacheable = pass.failedSources.isEmpty())
    }

    companion object {
        private const val DAY_S = 86_400L

        /** The cache budget of #61. A day of one thing with every tier and metric is about 100 KB. */
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

        /** The identity of one day's sources, sorted [sourceLine]s (the derive signature without the hash). */
        fun signature(files: List<ArchiveRecord>, templates: FileTemplates): String = files
            .map { sourceLine(it, templates) }
            .sorted()
            .joinToString("\n")

        /**
         * One source of a day signature on both phones, `name:crc32[:est<anchor>:<first>]:t<template>`.
         * The template is [FileTemplates.digestOf], `-` when none resolves, so a template served after the
         * file was indexed rebuilds the file's days.
         */
        fun sourceLine(file: ArchiveRecord, templates: FileTemplates): String =
            "${file.name}:${file.crc32}" + (if (file.hasEstimatedUtc) ":est${file.estAnchorFile}:${file.estFirstUtcNs}" else "") +
                ":t${templates.digestOf(file) ?: "-"}"
    }
}
