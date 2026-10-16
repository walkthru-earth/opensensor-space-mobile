package earth.walkthru.opensensor.files

import earth.walkthru.aqsync.archive.ArchiveKey
import earth.walkthru.aqsync.archive.ArchiveStore
import earth.walkthru.aqsync.data.history.ParquetIndex
import earth.walkthru.aqsync.data.history.ParquetIndexer
import earth.walkthru.aqsync.data.history.UtcColumns
import earth.walkthru.aqsync.data.history.parquet.ColumnValues
import earth.walkthru.aqsync.data.history.parquet.ParquetCodec
import earth.walkthru.aqsync.data.history.parquet.ParquetFile
import earth.walkthru.aqsync.data.history.parquet.ParquetFormatException
import earth.walkthru.aqsync.data.history.parquet.ParquetLogicalType
import earth.walkthru.aqsync.data.history.parquet.ParquetType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource

/** One decoded column, summarised for the table. Values are formatted here once, not per frame. */
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
 * real files. Read only, through [ArchiveStore.reader]. Nothing here changes the archive.
 */
object FileInspector {
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
                    RowGroupSummary(g, rg.numRows, rg.totalByteSize, s?.minLong(ParquetType.INT64)?.times(scale!!), s?.maxLong(ParquetType.INT64)?.times(scale!!))
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

    private fun summarize(name: String, type: ParquetType, timestampUnit: String?, col: ColumnValues, statsMin: String?, statsMax: String?): ColumnSummary {
        var first: String? = null
        var last: String? = null
        var minD = Double.POSITIVE_INFINITY
        var maxD = Double.NEGATIVE_INFINITY
        var minL = Long.MAX_VALUE
        var maxL = Long.MIN_VALUE
        var any = false
        for (i in 0 until col.size) {
            if (col.isNull(i)) continue
            any = true
            val text = format(col, i)
            if (first == null) first = text
            last = text
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
        return ColumnSummary(name, type, timestampUnit, col.size, col.nullCount, minText, maxText, first ?: NO_VALUE, last ?: NO_VALUE, statsMin, statsMax)
    }

    private fun unitLabel(unit: ParquetLogicalType.TimeUnit): String = when (unit) {
        ParquetLogicalType.TimeUnit.MILLIS -> "ms"
        ParquetLogicalType.TimeUnit.MICROS -> "us"
        ParquetLogicalType.TimeUnit.NANOS -> "ns"
        ParquetLogicalType.TimeUnit.UNKNOWN -> "?"
    }

    private fun format(col: ColumnValues, i: Int): String = when (col) {
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
    private var loaded: Pair<String, String>? = null

    fun load(station: String, name: String) {
        if (loaded == station to name) return
        loaded = station to name
        _state.value = FileMetricsState.Loading
        scope.launch {
            _state.value = withContext(io) { FileInspector.describe(store, station, name) }
        }
    }
}
