package earth.walkthru.aqsync.publicfeed

import earth.walkthru.aqsync.data.history.parquet.FooterCache
import earth.walkthru.aqsync.data.history.parquet.RangePolicy
import earth.walkthru.aqsync.data.history.parquet.RangeSource
import earth.walkthru.aqsync.net.BlockCache
import earth.walkthru.aqsync.net.BodyLimit
import earth.walkthru.aqsync.net.CachedRangeSource
import earth.walkthru.aqsync.net.HttpRangeEngine
import earth.walkthru.aqsync.net.HttpRangeException
import earth.walkthru.aqsync.net.HttpRangeSource
import earth.walkthru.aqsync.storage.ByteBudgetLru
import earth.walkthru.aqsync.storage.MemoryPressure
import earth.walkthru.aqsync.storage.MemoryTrimmable
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
import kotlin.time.Clock

/**
 * The live public feed, read from the OpenAQ aggregates collection on source.coop
 * (docs/devices-and-discovery.md). Anonymous, read-only GETs, no identifiers.
 *
 * - [latest] GETs `manifest.json` and answers `FeedPartition(date = as_of, hour = 0, generation)`, `as_of` being
 *   the newest complete PM2.5 day. The answer is kept for [MANIFEST_TTL_MS] by [clock]. A manifest
 *   without `as_of` answers null.
 * - [cells] reads `cells/h3_res=<res>/data.parquet` through an [HttpRangeSource], with one
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

    /** Guards [daily], [inflight], [generation], [lastManifest] and [files]. Never held across a network read. */
    private val rowsLock = Mutex()
    private val daily = ByteBudgetLru<DailyKey, CellsTable>(budgetBytes) { _, table -> estimateBytes(table) }
    private val inflight = HashMap<DailyKey, CompletableDeferred<CellsTable>>()

    /** Bumped whenever [daily] is dropped, so a download that started before is not kept after. */
    private var generation = 0L

    /** The partition last read, which carries the manifest `generation`. */
    private var lastManifest: FeedPartition? = null

    /** The manifest `generation` of the files, part of the block cache key with `as_of`. */
    private var files: String? = null

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
        val manifest = readManifest()
        val partition = manifest.asOf?.let { FeedPartition(LocalDate.parse(it).toString(), 0, manifest.generation) }
        cached = now to partition
        if (partition != null) {
            // A new as_of or generation replaces the files whole. Rows of the old one are never served again.
            rowsLock.withLock {
                if (partition != lastManifest) {
                    if (lastManifest != null) clearRows()
                    lastManifest = partition
                    files = manifest.generation
                }
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

    /** The kept daily rows of [key], or one download shared with every concurrent caller of [key]. */
    private suspend fun dailyRows(key: DailyKey): CellsTable {
        while (true) {
            var owner = false
            var startedFiles: String? = null
            val (deferred, startedAt) = rowsLock.withLock {
                daily[key]?.let { return it }
                startedFiles = files
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
                val table = reader.readCells(source(key, startedFiles), ranges = null, days = days)
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

    /** The cells file of [key], through the block cache keyed on `as_of` and the manifest generation [files]. */
    private fun source(key: DailyKey, files: String?): RangeSource {
        val url = cellsUrl(key.res)
        val http = HttpRangeSource(url, engine)
        return blocks?.let { CachedRangeSource(http, it, url, generation = "${key.asOf}\n${files.orEmpty()}") } ?: http
    }

    /** The URL of the cells file at [res]. */
    fun cellsUrl(res: Int): String = "$root/cells/h3_res=$res/data.parquet"

    private suspend fun readManifest(): Manifest {
        val url = "$root/$MANIFEST"
        val response = engine.get(url, range = null, ifRange = null, ifNoneMatch = null, limit = BodyLimit(MANIFEST_MAX_BYTES, MANIFEST_MAX_BYTES))
        if (response.status != 200) throw HttpRangeException(response.status, url)
        return FeedPaths.json.decodeFromString<Manifest>(response.body.decodeToString())
    }

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

        /** How long an answer of [latest] stands before the manifest is read again. */
        const val MANIFEST_TTL_MS: Long = 5 * 60 * 1000L

        /** The manifest is a few KB. Anything far larger is not the manifest. */
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
    }
}
