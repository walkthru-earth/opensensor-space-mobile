package space.opensensor.publicfeed

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.opensensor.data.history.parquet.FooterCache
import space.opensensor.data.history.parquet.RangePolicy
import space.opensensor.data.history.parquet.RangeSource
import space.opensensor.net.BlockCache
import space.opensensor.net.BodyLimit
import space.opensensor.net.CachedRangeSource
import space.opensensor.net.HttpRangeEngine
import space.opensensor.net.HttpRangeException
import space.opensensor.net.HttpRangeSource
import space.opensensor.storage.ByteBudgetLru
import space.opensensor.storage.MemoryPressure
import space.opensensor.storage.MemoryTrimmable
import kotlin.time.Clock

/**
 * The live public feed, read from the OpenAQ aggregates collection on source.coop
 * (docs/devices-and-discovery.md). Anonymous, read-only GETs, no identifiers.
 *
 * - [latest] GETs the discover Collection `collection.json` ([DiscoverCollection]) and answers
 *   `FeedPartition(date = as_of, hour = 0, generation = updated)`, `as_of` being the newest complete
 *   day. When the Collection is missing (404 or 403) it GETs `manifest.json` instead and answers
 *   `FeedPartition(date = as_of, hour = 0, generation)`. The answer is kept for [MANIFEST_TTL_MS] by
 *   [clock]. A feed without `as_of` answers null.
 * - [cells] describes the properties with the Collection's titles and scales, or with
 *   [DiscoverCollection.BUNDLED] for a manifest feed. [resolutionFor] snaps a zoom to the nearest
 *   resolution the Collection lists. One it has no asset for answers an empty table without a request.
 * - [cells] reads the cells file of `res` through an [HttpRangeSource], with one
 *   [FooterCache] for the feed, keeps the daily rows of the 7 days ending at the partition date and
 *   folds them with [DailyCellFold]. With [blocks] the source is a [CachedRangeSource] keyed by URL,
 *   `as_of` and the manifest `generation`, so a later read of the same partition, also after a restart, takes the blocks from disk
 *   with no range request. The files hold daily rows only, so [FeedWindow.LAST_HOUR] answers
 *   [CellsTable.EMPTY] without a request.
 * - The live files are one row group with no page index, so any read fetches the whole file. The daily
 *   rows of one `(res, as_of)` are therefore kept in memory, at most [MAX_RESOLUTIONS] resolutions and
 *   [budgetBytes], least recently used first out. The 24 hour and 7 day windows of one viewport and
 *   every later pan at that resolution read the network once, and `ranges` filters the kept rows.
 *   Concurrent callers for one key share one download. A failure is never kept. A new `as_of` or
 *   `generation` from [latest] drops every kept row, and [trim] gives them back on [MemoryPressure].
 *
 * Transport, HTTP and Parquet failures propagate as exceptions; the caller decides what to show.
 */
class OpenAqAggregatesFeed(
    private val engine: HttpRangeEngine,
    root: String = DEFAULT_ROOT,
    private val clock: Clock = Clock.System,
    policy: RangePolicy = RangePolicy.CELLULAR,
    budgetBytes: Long = DEFAULT_BUDGET_BYTES,
    /** The disk block cache, or null to read every block from the network. */
    private val blocks: BlockCache? = null,
) : PublicFeed,
    MemoryTrimmable {
    private val root = root.trimEnd('/')
    private val reader = ParquetFeedReader(FooterCache(), policy)
    private val mutex = Mutex()
    private var cached: Pair<Long, FeedPartition?>? = null

    /** Guards [daily], [inflight], [generation], [lastManifest], [files] and [layout]. Never held across a network read. */
    private val rowsLock = Mutex()
    private val daily = ByteBudgetLru<DailyKey, CellsTable>(budgetBytes) { _, table -> estimateBytes(table) }
    private val inflight = HashMap<DailyKey, CompletableDeferred<CellsTable>>()

    /** Bumped whenever [daily] is dropped, so a download that started before is not kept after. */
    private var generation = 0L

    /** The partition last read, which carries the manifest `generation`. */
    private var lastManifest: FeedPartition? = null

    /** The manifest `generation` or Collection `updated` of the files, part of the block cache key with `as_of`. */
    private var files: String? = null

    /** The discover Collection last read, null for a manifest feed. */
    private var layout: DiscoverCollection? = null

    /** The resolutions whose daily rows are kept now. */
    suspend fun cachedResolutions(): Int = rowsLock.withLock { daily.size }

    override suspend fun trim(level: MemoryPressure.Level) = rowsLock.withLock { daily.trim(level) }

    override val isSample: Boolean get() = false
    override val isDaily: Boolean get() = true
    override val available: Boolean get() = reader.available
    override val windows: List<FeedWindow> get() = WINDOWS

    override suspend fun latest(): FeedPartition? = mutex.withLock {
        val now = clock.now().toEpochMilliseconds()
        cached?.let { (at, partition) -> if (now - at in 0 until MANIFEST_TTL_MS) return@withLock partition }
        val (partition, collection) = readIndex()
        cached = now to partition
        if (partition != null) {
            // A new as_of or generation replaces the files whole. Rows of the old one are never served again.
            rowsLock.withLock {
                if (partition != lastManifest || collection?.cells != layout?.cells) {
                    if (lastManifest != null) clearRows()
                    lastManifest = partition
                    files = partition.generation
                }
                layout = collection
            }
        }
        partition
    }

    override suspend fun cells(partition: FeedPartition, window: FeedWindow, res: Int, ranges: List<LongRange>?): CellsTable {
        if (window !in WINDOWS) return CellsTable.EMPTY
        val asOf = LocalDate.parse(partition.date)
        val table = dailyRows(DailyKey(res, asOf))
        val rows = if (ranges == null) table.rows else table.rows.filter { row -> ranges.any { row.h3 in it } }
        return table.copy(
            info = table.info.copy(sample = false, window = window.key),
            rows = DailyCellFold.fold(rows, window, asOf),
        )
    }

    /**
     * The kept daily rows of [key], or one download shared with every concurrent caller of [key].
     * [CellsTable.EMPTY] without a request when the Collection has no file for the resolution.
     */
    private suspend fun dailyRows(key: DailyKey): CellsTable {
        while (true) {
            var owner = false
            var startedFiles: String? = null
            var startedLayout: DiscoverCollection? = null
            val (deferred, startedAt) = rowsLock.withLock {
                daily[key]?.let { return it }
                startedFiles = files
                startedLayout = layout
                if (cellsUrl(key.res, layout) == null) return CellsTable.EMPTY
                val running = inflight[key]
                if (running != null) {
                    running to generation
                } else {
                    owner = true
                    CompletableDeferred<CellsTable>().also { inflight[key] = it } to generation
                }
            }
            if (!owner) {
                try {
                    return deferred.await()
                } catch (_: Abandoned) {
                    // The caller that downloaded was cancelled. Try again, maybe as the one who downloads.
                    continue
                }
            }
            try {
                val days = key.asOf.minus(DatePeriod(days = DAYS - 1))..key.asOf
                val read = reader.readCells(source(key, startedFiles, startedLayout), ranges = null, days = days)
                val described = (startedLayout ?: DiscoverCollection.BUNDLED).describe(read.info.properties)
                val table = read.copy(info = read.info.copy(properties = described))
                rowsLock.withLock {
                    if (inflight[key] === deferred) inflight.remove(key)
                    if (generation == startedAt) {
                        daily[key] = table
                        while (daily.size > MAX_RESOLUTIONS) daily.remove(daily.keys.first())
                    }
                }
                deferred.complete(table)
                return table
            } catch (e: Throwable) {
                withContext(NonCancellable) { rowsLock.withLock { if (inflight[key] === deferred) inflight.remove(key) } }
                deferred.completeExceptionally(if (e is CancellationException) Abandoned() else e)
                throw e
            }
        }
    }

    private fun clearRows() {
        daily.clear()
        generation++
    }

    /** The cells file of [key], through the block cache keyed on `as_of` and the generation [files]. */
    private fun source(key: DailyKey, files: String?, collection: DiscoverCollection?): RangeSource {
        val url = checkNotNull(cellsUrl(key.res, collection)) { "no cells file at resolution ${key.res}" }
        val http = HttpRangeSource(url, engine)
        return blocks?.let { CachedRangeSource(http, it, url, generation = "${key.asOf}\n${files.orEmpty()}") } ?: http
    }

    /** The nearest resolution the Collection lists ([DiscoverCollection.resolutionNear]), [res] for a manifest feed. */
    override suspend fun resolutionFor(res: Int): Int = rowsLock.withLock { layout }?.resolutionNear(res) ?: res

    /**
     * The URL of the cells file at [res]. The Collection's asset for it, or null when it has none.
     * The manifest layout `cells/h3_res=<res>/data.parquet` when there is no Collection.
     */
    suspend fun cellsUrl(res: Int): String? = cellsUrl(res, rowsLock.withLock { layout })

    private fun cellsUrl(res: Int, collection: DiscoverCollection?): String? =
        if (collection != null) collection.cells[res] else "$root/cells/h3_res=$res/data.parquet"

    /** The Collection's partition and the Collection, else the manifest's partition when the Collection is missing. */
    private suspend fun readIndex(): Pair<FeedPartition?, DiscoverCollection?> {
        val url = "$root/${DiscoverCollection.FILE}"
        val response = getIndex(url)
        if (response.status == 200) {
            val collection = DiscoverCollection.parse(response.body.decodeToString(), root)
            return collection.asOf?.let { FeedPartition(it.toString(), 0, collection.generation) } to collection
        }
        if (response.status !in MISSING) throw HttpRangeException(response.status, url)
        val manifest = readManifest()
        return manifest.asOf?.let { FeedPartition(LocalDate.parse(it).toString(), 0, manifest.generation) } to null
    }

    private suspend fun readManifest(): Manifest {
        val url = "$root/$MANIFEST"
        val response = getIndex(url)
        if (response.status != 200) throw HttpRangeException(response.status, url)
        return FeedPaths.json.decodeFromString<Manifest>(response.body.decodeToString())
    }

    private suspend fun getIndex(url: String) =
        engine.get(url, range = null, ifRange = null, ifNoneMatch = null, limit = BodyLimit(MANIFEST_MAX_BYTES, MANIFEST_MAX_BYTES))

    private data class DailyKey(val res: Int, val asOf: LocalDate)

    /** Tells the waiters of a download that its caller was cancelled before it finished. */
    private class Abandoned : RuntimeException("download abandoned")

    @Serializable
    internal data class Manifest(
        @SerialName("as_of") val asOf: String? = null,
        val generation: String? = null,
    )

    companion object {
        const val DEFAULT_ROOT: String = "https://data.source.coop/walkthru-earth/opensensor-space/openaq-aggregates"
        const val MANIFEST: String = "manifest.json"

        /** How long an answer of [latest] stands before the Collection or manifest is read again. */
        const val MANIFEST_TTL_MS: Long = 5 * 60 * 1000L

        /** The Collection and the manifest are a few KB. Anything far larger is neither. */
        const val MANIFEST_MAX_BYTES: Long = 1024L * 1024

        /** The most resolutions whose daily rows are kept. A zoom spans about two. */
        const val MAX_RESOLUTIONS: Int = 4

        /** About a few thousand cells a week at the finest map resolution. */
        const val DEFAULT_BUDGET_BYTES: Long = 4L * 1024 * 1024

        /** A rough size of a kept table, enough for the budget. */
        private fun estimateBytes(table: CellsTable): Long =
            table.rows.sumOf { row -> ROW_BYTES + STAT_BYTES * row.stats.size }.toLong()

        private const val ROW_BYTES = 96
        private const val STAT_BYTES = 96
        private const val DAYS = 7
        private val WINDOWS = listOf(FeedWindow.DAY, FeedWindow.WEEK)

        /** Answers that mean "no Collection here". S3 answers 403 for a missing key without list rights. */
        private val MISSING = setOf(403, 404)
    }
}
