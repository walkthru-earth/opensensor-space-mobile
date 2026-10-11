package earth.walkthru.aqsync.publicfeed

import earth.walkthru.aqsync.data.history.parquet.FooterCache
import earth.walkthru.aqsync.data.history.parquet.RangePolicy
import earth.walkthru.aqsync.net.BodyLimit
import earth.walkthru.aqsync.net.HttpRangeEngine
import earth.walkthru.aqsync.net.HttpRangeException
import earth.walkthru.aqsync.net.HttpRangeSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * - [latest] GETs `manifest.json` and answers `FeedPartition(date = as_of, hour = 0)`, `as_of` being
 *   the newest complete PM2.5 day. The answer is kept for [MANIFEST_TTL_MS] by [clock]. A manifest
 *   without `as_of` answers null.
 * - [cells] reads `cells/h3_res=<res>/data.parquet` through an [HttpRangeSource], with one
 *   [FooterCache] for the feed and row group pruning on `h3_index`, keeps the daily rows of the 7 days
 *   ending at the partition date and folds them with [DailyCellFold]. The files hold daily rows only,
 *   so [FeedWindow.LAST_HOUR] answers [CellsTable.EMPTY] without a request.
 *
 * Transport, HTTP and Parquet failures propagate as exceptions; the caller decides what to show.
 */
class OpenAqAggregatesFeed(
    private val engine: HttpRangeEngine,
    root: String = DEFAULT_ROOT,
    private val clock: Clock = Clock.System,
    policy: RangePolicy = RangePolicy.CELLULAR,
) : PublicFeed {
    private val root = root.trimEnd('/')
    private val reader = ParquetFeedReader(FooterCache(), policy)
    private val mutex = Mutex()
    private var cached: Pair<Long, FeedPartition?>? = null

    override val isSample: Boolean get() = false
    override val available: Boolean get() = reader.available
    override val windows: List<FeedWindow> get() = WINDOWS

    override suspend fun latest(): FeedPartition? = mutex.withLock {
        val now = clock.now().toEpochMilliseconds()
        cached?.let { (at, partition) -> if (now - at in 0 until MANIFEST_TTL_MS) return@withLock partition }
        val partition = readManifest().asOf?.let { FeedPartition(LocalDate.parse(it).toString(), 0) }
        cached = now to partition
        partition
    }

    override suspend fun cells(partition: FeedPartition, window: FeedWindow, res: Int, ranges: List<LongRange>?): CellsTable {
        if (window !in WINDOWS) return CellsTable.EMPTY
        val asOf = LocalDate.parse(partition.date)
        val days = asOf.minus(DatePeriod(days = DAYS - 1))..asOf
        val table = reader.readCells(HttpRangeSource(cellsUrl(res), engine), ranges, days)
        return table.copy(
            info = table.info.copy(sample = false, window = window.key),
            rows = DailyCellFold.fold(table.rows, window, asOf),
        )
    }

    /** The URL of the cells file at [res]. */
    fun cellsUrl(res: Int): String = "$root/cells/h3_res=$res/data.parquet"

    private suspend fun readManifest(): Manifest {
        val url = "$root/$MANIFEST"
        val response = engine.get(url, range = null, ifRange = null, ifNoneMatch = null, limit = BodyLimit(MANIFEST_MAX_BYTES, MANIFEST_MAX_BYTES))
        if (response.status != 200) throw HttpRangeException(response.status, url)
        return FeedPaths.json.decodeFromString<Manifest>(response.body.decodeToString())
    }

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

        private const val DAYS = 7
        private val WINDOWS = listOf(FeedWindow.DAY, FeedWindow.WEEK)
    }
}
