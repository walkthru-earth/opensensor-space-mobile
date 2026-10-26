package space.opensensor.files

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import space.opensensor.archive.ArchiveKey
import space.opensensor.archive.ArchiveStore
import space.opensensor.data.history.ParquetIndex
import space.opensensor.data.history.ParquetIndexer
import space.opensensor.data.history.UtcColumns
import space.opensensor.data.history.parquet.ColumnValues
import space.opensensor.data.history.parquet.ParquetCodec
import space.opensensor.data.history.parquet.ParquetFile
import space.opensensor.data.history.parquet.ParquetFormatException
import space.opensensor.data.history.parquet.ParquetLogicalType
import space.opensensor.data.history.parquet.ParquetType
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource

/** One decoded column, summarised for the table. Only its first and last values are formatted, once. */
data class ColumnSummary(
    val name: String,
    val type: ParquetType,
    /** The UTC `TIMESTAMP` unit as `us`, `ns` or `ms`, else null. */
    val timestampUnit: String?,
    val rows: Int,
    val nulls: Int,
    val min: String,
    val max: String,
    val first: String,
    val last: String,
    /** Footer statistics (v6 writer) for cross-checking against the decode, else null. */
    val statsMin: String?,
    val statsMax: String?,
)

/**
 * One page of rows, keyed by row index (#98). [from] is the index of the first row on the page in the
 * file, [rows] holds one formatted cell per column of [columns]. Only these rows were formatted.
 */
data class RowPage(val from: Long, val totalRows: Long, val columns: List<String>, val rows: List<List<String>>) {
    /** The index after the last row on the page. */
    val to: Long get() = from + rows.size
}

/** What the Rows card shows. */
sealed interface RowsState {
    /** No page asked for yet on this file. */
    data object Idle : RowsState

    data object Loading : RowsState

    data class Shown(val page: RowPage) : RowsState

    /** The page at [from] could not be read, for example the file went away after the summary loaded. */
    data class Unavailable(val from: Long) : RowsState
}

data class RowGroupSummary(val ordinal: Int, val rows: Long, val bytes: Long, val firstUtcNs: Long?, val lastUtcNs: Long?)

enum class FailReason { NOT_IN_ARCHIVE, READER_REFUSED, UNREADABLE }

sealed interface FileMetricsState {
    data object Loading : FileMetricsState

    /** [detail] is the reader's technical message, shown at Developer level only. */
    data class Failed(val reason: FailReason, val detail: String? = null) : FileMetricsState
    data class Loaded(
        val station: String,
        val name: String,
        val sizeBytes: Long,
        val index: ParquetIndex,
        val codec: ParquetCodec?,
        val footerBytes: Int,
        val columnOrders: Int,
        val rowGroups: List<RowGroupSummary>,
        val columns: List<ColumnSummary>,
        val metadata: Map<String, String>,
        val decodeMillis: Long,
    ) : FileMetricsState
}

/**
 * Opens one archived file with the app's own Parquet reader (`:core:parquet`, the same on Android and
 * iOS) and decodes every column, so the Metrics screen doubles as the on-phone proof of the reader on
 * real files. A column's summary formats only its first and last value. Rows are formatted one
 * [page] at a time, from the row groups that hold them (#98). Read only, through
 * [ArchiveStore.reader]. Nothing here changes the archive.
 */
object FileInspector {
    /** Rows on one [page]. */
    const val PAGE_ROWS = 20

    /**
     * Rows [from] until `from + size` of the file, or null when it cannot be read. [from] is clamped
     * into the file, so a page past the end shows the last rows.
     */
    suspend fun page(store: ArchiveStore, station: String, name: String, from: Long, size: Int = PAGE_ROWS): RowPage? {
        val key = runCatching { ArchiveKey(station, name) }.getOrNull() ?: return null
        return try {
            if (!store.exists(key)) return null
            ParquetFile.open(store.reader(key), name).use { page(it, from, size) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The summary already shows why the file cannot be read.
            null
        }
    }

    /** One page of [pf]. Each column decodes only the row groups the page overlaps, each cell is formatted once with [format]. */
    internal fun page(pf: ParquetFile, from: Long, size: Int, format: (ColumnValues, Int) -> String = ::formatCell): RowPage {
        require(size > 0) { "page size must be positive" }
        val total = pf.numRows
        val start = from.coerceIn(0L, maxOf(0L, total - size))
        val end = minOf(total, start + size)
        val leaves = pf.leaves
        val cells = Array((end - start).toInt()) { arrayOfNulls<String>(leaves.size) }
        var groupStart = 0L
        pf.metadata.rowGroups.forEachIndexed { g, rg ->
            val groupEnd = groupStart + rg.numRows
            val lo = maxOf(start, groupStart)
            val hi = minOf(end, groupEnd)
            if (lo < hi) {
                leaves.forEachIndexed { c, leaf ->
                    val col = pf.readColumn(leaf, g)
                    for (r in lo until hi) {
                        val i = (r - groupStart).toInt()
                        cells[(r - start).toInt()][c] = if (col.isNull(i)) NO_VALUE else format(col, i)
                    }
                }
            }
            groupStart = groupEnd
        }
        return RowPage(start, total, leaves.map { it.name }, cells.map { row -> row.map { it ?: NO_VALUE } })
    }

    suspend fun describe(store: ArchiveStore, station: String, name: String): FileMetricsState {
        val key = runCatching { ArchiveKey(station, name) }.getOrNull()
            ?: return FileMetricsState.Failed(FailReason.NOT_IN_ARCHIVE, name)
        return try {
            if (!store.exists(key)) return FileMetricsState.Failed(FailReason.NOT_IN_ARCHIVE, name)
            val mark = TimeSource.Monotonic.markNow()
            val reader = store.reader(key)
            // Closes the file and its reader before returning (#61).
            ParquetFile.open(reader, name).use { pf ->
                val index = ParquetIndexer.index(pf)
                val md = pf.metadata
                val utcLeaf = UtcColumns.eventLeaf(md)
                // Footer statistics are in the column's own unit, the summary is in nanoseconds.
                val scale = utcLeaf?.let { runCatching { UtcColumns.nanosPerUnit(it) }.getOrNull() }
                val groups = md.rowGroups.mapIndexed { g, rg ->
                    val s = utcLeaf?.takeIf { scale != null }?.let { rg.columns[it.index].statistics }
                    RowGroupSummary(g, rg.numRows, rg.totalByteSize, s?.minLong(ParquetType.INT64)?.let { UtcColumns.scaleToNs(it, scale!!) }, s?.maxLong(ParquetType.INT64)?.let { UtcColumns.scaleToNs(it, scale!!) })
                }
                val columns = pf.leaves.map { leaf ->
                    val col = pf.readColumn(leaf)
                    val stats = md.rowGroups.firstOrNull()?.columns?.get(leaf.index)?.statistics?.takeIf { md.rowGroups.size == 1 && it.hasMinMax }
                    val float = leaf.type == ParquetType.FLOAT
                    summarize(
                        leaf.name,
                        leaf.type,
                        leaf.timestampUtcUnit?.let(::unitLabel),
                        col,
                        stats?.let { s -> if (float) s.minFloat()?.toString() else s.minLong(leaf.type)?.toString() },
                        stats?.let { s -> if (float) s.maxFloat()?.toString() else s.maxLong(leaf.type)?.toString() },
                    )
                }
                FileMetricsState.Loaded(
                    station = station,
                    name = name,
                    sizeBytes = reader.size,
                    index = index,
                    codec = md.rowGroups.firstOrNull()?.columns?.firstOrNull()?.codec,
                    footerBytes = md.footerLength,
                    columnOrders = md.columnOrders.size,
                    rowGroups = groups,
                    columns = columns,
                    metadata = md.keyValueMetadata,
                    decodeMillis = mark.elapsedNow().inWholeMilliseconds,
                )
            }
        } catch (e: ParquetFormatException) {
            FileMetricsState.Failed(FailReason.READER_REFUSED, e.message)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A missing final throws from the store, any other read error lands here too.
            FileMetricsState.Failed(FailReason.UNREADABLE, e.message)
        }
    }

    internal fun summarize(
        name: String,
        type: ParquetType,
        timestampUnit: String?,
        col: ColumnValues,
        statsMin: String?,
        statsMax: String?,
        format: (ColumnValues, Int) -> String = ::formatCell,
    ): ColumnSummary {
        // Indexes only. Formatting every value of a long column cost a string per row (#98).
        var first = -1
        var last = -1
        var minD = Double.POSITIVE_INFINITY
        var maxD = Double.NEGATIVE_INFINITY
        var minL = Long.MAX_VALUE
        var maxL = Long.MIN_VALUE
        var any = false
        for (i in 0 until col.size) {
            if (col.isNull(i)) continue
            any = true
            if (first < 0) first = i
            last = i
            when (col) {
                is ColumnValues.Float32, is ColumnValues.Float64 -> {
                    val v = col.asDouble(i)!!
                    if (!v.isNaN()) {
                        if (v < minD) minD = v
                        if (v > maxD) maxD = v
                    }
                }
                // Strings and WKB have no numeric range.
                is ColumnValues.Binary -> Unit
                else -> {
                    val v = col.asLong(i)!!
                    if (v < minL) minL = v
                    if (v > maxL) maxL = v
                }
            }
        }
        val (minText, maxText) = when {
            !any -> NO_VALUE to NO_VALUE
            col is ColumnValues.Float32 || col is ColumnValues.Float64 ->
                (if (minD.isFinite()) formatValue(minD) else NO_VALUE) to (if (maxD.isFinite()) formatValue(maxD) else NO_VALUE)
            col is ColumnValues.Binary -> NO_VALUE to NO_VALUE
            else -> minL.toString() to maxL.toString()
        }
        val firstText = if (first < 0) NO_VALUE else format(col, first)
        val lastText = if (last < 0) NO_VALUE else format(col, last)
        return ColumnSummary(name, type, timestampUnit, col.size, col.nullCount, minText, maxText, firstText, lastText, statsMin, statsMax)
    }

    private fun unitLabel(unit: ParquetLogicalType.TimeUnit): String = when (unit) {
        ParquetLogicalType.TimeUnit.MILLIS -> "ms"
        ParquetLogicalType.TimeUnit.MICROS -> "us"
        ParquetLogicalType.TimeUnit.NANOS -> "ns"
        ParquetLogicalType.TimeUnit.UNKNOWN -> "?"
    }

    private fun formatCell(col: ColumnValues, i: Int): String = when (col) {
        is ColumnValues.Float32 -> formatValue(col.values[i].toDouble())
        is ColumnValues.Float64 -> formatValue(col.values[i])
        is ColumnValues.Int32 -> col.values[i].toString()
        is ColumnValues.Int64 -> col.values[i].toString()
        is ColumnValues.Binary -> if (col.length(i) <= 64) col.string(i) ?: "" else "${col.length(i)} B"
    }
}

/** Loads one file's metrics off the main thread. Android wraps it in a ViewModel, iOS holds it in AppRoot. */
class FileMetricsController(
    private val scope: CoroutineScope,
    private val store: ArchiveStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _state = MutableStateFlow<FileMetricsState>(FileMetricsState.Loading)
    val state: StateFlow<FileMetricsState> = _state.asStateFlow()

    private val _rows = MutableStateFlow<RowsState>(RowsState.Idle)

    /** The page of rows last asked for with [page]. */
    val rows: StateFlow<RowsState> = _rows.asStateFlow()
    private var loaded: Pair<String, String>? = null
    private var paging: Job? = null

    fun load(station: String, name: String) {
        if (loaded == station to name) return
        loaded = station to name
        _state.value = FileMetricsState.Loading
        paging?.cancel()
        _rows.value = RowsState.Idle
        scope.launch {
            _state.value = withContext(io) { FileInspector.describe(store, station, name) }
        }
    }

    /** Reads the page of rows starting at row [from] of the loaded file. A newer request replaces an older one. */
    fun page(from: Long) {
        val (station, name) = loaded ?: return
        paging?.cancel()
        _rows.value = RowsState.Loading
        paging = scope.launch {
            val page = withContext(io) { FileInspector.page(store, station, name, from) }
            _rows.value = page?.let(RowsState::Shown) ?: RowsState.Unavailable(from)
        }
    }
}
