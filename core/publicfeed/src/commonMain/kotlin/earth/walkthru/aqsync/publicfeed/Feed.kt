package earth.walkthru.aqsync.publicfeed

import earth.walkthru.aqsync.data.history.parquet.MemoryRangeSource
import earth.walkthru.aqsync.data.history.parquet.RangeSource
import earth.walkthru.aqsync.storage.ByteBudgetLru
import earth.walkthru.aqsync.storage.MemoryPressure
import earth.walkthru.aqsync.storage.MemoryTrimmable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The averaging window of a cells file. Public values are hourly means, never "now". */
enum class FeedWindow(val key: String) {
    LAST_HOUR("1h"),
    DAY("24h"),
    WEEK("7d"),
    ;

    fun next(): FeedWindow = entries[(ordinal + 1) % entries.size]
}

/** One `date=`/`hour=` partition of the feed, the newest complete hour. */
@Serializable
data class FeedPartition(val date: String, val hour: Int)

/** One property from the dictionary named in the file's key value metadata. */
@Serializable
data class FeedProperty(
    val id: String,
    val label: String,
    val unit: String,
    val category: String,
    val precision: Double? = null,
)

@Serializable
internal data class FeedDictionary(val version: Int = 1, val properties: List<FeedProperty> = emptyList())

/** Statistics of one property in one cell. Null fields stay null, never zero. */
data class CellStat(val n: Int?, val mean: Double?, val min: Double?, val max: Double?)

/** One published cell. [devices] is always 3 or more (the publisher applies the crowd rule). */
data class CellRow(
    val h3: Long,
    val devices: Int,
    /** End of the newest hour that holds a value, epoch milliseconds UTC. */
    val updatedEpochMs: Long,
    val stats: Map<String, CellStat>,
) {
    fun mean(property: String): Double? = stats[property]?.mean
}

/** The facts of one cells file the screen needs. */
data class CellsFileInfo(
    val sample: Boolean,
    val window: String?,
    val h3Res: Int?,
    val properties: List<FeedProperty>,
)

/** Rows read from one cells file, plus how much of it was read. */
data class CellsTable(
    val info: CellsFileInfo,
    val rows: List<CellRow>,
    val rowGroupsRead: Int,
    val rowGroupsTotal: Int,
    /** Data pages decoded, of the pages in the row groups that were read. */
    val pagesRead: Int = 0,
    val pagesTotal: Int = 0,
) {
    companion object {
        val EMPTY = CellsTable(CellsFileInfo(sample = false, window = null, h3Res = null, properties = emptyList()), emptyList(), 0, 0)
    }
}

/**
 * The narrow Parquet port of the feed. One cells file in, matching rows out. [ParquetFeedReader]
 * implements it in common code over the portable `:core:parquet` reader. The byte source is the
 * only thing that changes between the bundled sample (memory) and the live feed (HTTP ranges).
 */
interface FeedReader {
    /** False when this platform cannot read Parquet. */
    val available: Boolean get() = true

    /**
     * Rows whose `h3_index` falls in [ranges] (all rows when null). Row groups and pages whose
     * `h3_index` statistics miss every range are skipped without fetching them. Closes [source].
     */
    suspend fun readCells(source: RangeSource, ranges: List<LongRange>?): CellsTable
}

/** The public feed port. The bundled sample and, later, the live source.coop feed implement it. */
interface PublicFeed {
    /** True while the feed is the bundled sample. The map then shows the "Sample data" pill. */
    val isSample: Boolean

    /** False when this platform cannot read the feed at all yet. */
    val available: Boolean

    /** The newest complete partition, or null when nothing is published. */
    suspend fun latest(): FeedPartition?

    suspend fun cells(partition: FeedPartition, window: FeedWindow, res: Int, ranges: List<LongRange>?): CellsTable
}

/** Paths in the live layout, relative to the feed root (`opensensor/discover/v1`). */
object FeedPaths {
    fun cells(partition: FeedPartition, window: FeedWindow, res: Int): String =
        "cells/window=${window.key}/date=${partition.date}/hour=${partition.hour.toString().padStart(2, '0')}/h3_res=$res/data.parquet"

    internal val json = Json { ignoreUnknownKeys = true }

    /** Parses the `dictionary` key value metadata. A missing or broken dictionary yields no properties. */
    fun parseDictionary(text: String?): List<FeedProperty> =
        text?.let { runCatching { json.decodeFromString<FeedDictionary>(it).properties }.getOrNull() }.orEmpty()
}

/**
 * The sample feed that ships in the app, in the live layout and read through the same
 * [FeedReader]. [load] returns the bytes of a bundled file by its path under [root]. `latest.json`
 * stands in for the S3 listing until the live feed exists. Loaded files stay in a least recently used
 * cache of [budgetBytes], which [trim] shrinks or drops on [MemoryPressure] (#61).
 */
class BundledSampleFeed(
    private val reader: FeedReader = ParquetFeedReader(),
    private val root: String = "files/discover-sample/v1",
    budgetBytes: Long = DEFAULT_BUDGET_BYTES,
    private val load: suspend (String) -> ByteArray,
) : PublicFeed,
    MemoryTrimmable {
    private val mutex = Mutex()
    private val bytes = ByteBudgetLru<String, ByteArray>(budgetBytes) { path, data -> data.size + 2L * path.length }

    /** Bytes the cache holds now. */
    suspend fun cachedBytes(): Long = mutex.withLock { bytes.sizeBytes }

    override suspend fun trim(level: MemoryPressure.Level) = mutex.withLock { bytes.trim(level) }

    override val isSample: Boolean get() = true
    override val available: Boolean get() = reader.available

    override suspend fun latest(): FeedPartition? = runCatching {
        FeedPaths.json.decodeFromString<FeedPartition>(load("$root/latest.json").decodeToString())
    }.getOrNull()

    override suspend fun cells(partition: FeedPartition, window: FeedWindow, res: Int, ranges: List<LongRange>?): CellsTable {
        if (!reader.available) return CellsTable.EMPTY
        val path = "$root/${FeedPaths.cells(partition, window, res)}"
        val data = mutex.withLock { bytes[path] } ?: load(path).also { loaded -> mutex.withLock { bytes.put(path, loaded) } }
        return reader.readCells(MemoryRangeSource(data, path, "bundled"), ranges)
    }

    companion object {
        /** Every bundled sample is about 280 KB together, so 1 MiB keeps them all in normal use. */
        const val DEFAULT_BUDGET_BYTES: Long = 1024L * 1024
    }
}
