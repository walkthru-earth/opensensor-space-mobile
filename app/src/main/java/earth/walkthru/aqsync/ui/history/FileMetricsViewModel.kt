package earth.walkthru.aqsync.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import earth.walkthru.aqsync.data.history.ParquetIndex
import earth.walkthru.aqsync.data.history.ParquetIndexer
import earth.walkthru.aqsync.data.history.parquet.ColumnValues
import earth.walkthru.aqsync.data.history.parquet.ParquetCodec
import earth.walkthru.aqsync.data.history.parquet.ParquetFile
import earth.walkthru.aqsync.data.history.parquet.ParquetFormatException
import earth.walkthru.aqsync.data.history.parquet.ParquetType
import earth.walkthru.aqsync.data.settings.AppSettings
import earth.walkthru.aqsync.data.settings.DetailLevel
import earth.walkthru.aqsync.data.sync.FileArchive
import earth.walkthru.aqsync.ui.components.NO_VALUE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** One decoded column, summarised for the table. Values are formatted here once, not per frame. */
data class ColumnSummary(
    val name: String,
    val type: ParquetType,
    val timestamp: Boolean,
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

sealed interface FileMetricsState {
    data object Loading : FileMetricsState
    data class Failed(val message: String) : FileMetricsState
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
 * Opens one archived file with the app's own Parquet reader and decodes *every* column, so the
 * Detailed/Developer "Metrics" screen doubles as the on-phone proof of the reader on real files.
 * Runs on IO; a file is at most a few hundred rows so this takes milliseconds.
 */
@HiltViewModel
class FileMetricsViewModel @Inject constructor(
    private val archive: FileArchive,
    settings: AppSettings,
) : ViewModel() {

    /** Detailed shows rows, time span and columns. Developer adds footer, codec, schema, row groups and metadata. */
    val detailLevel: StateFlow<DetailLevel> = settings.detailLevel.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailLevel.DETAILED)

    private val _state = MutableStateFlow<FileMetricsState>(FileMetricsState.Loading)
    val state: StateFlow<FileMetricsState> = _state.asStateFlow()

    fun load(station: String, name: String) {
        _state.value = FileMetricsState.Loading
        viewModelScope.launch {
            _state.value = withContext(Dispatchers.IO) { describe(station, name) }
        }
    }

    private fun describe(station: String, name: String): FileMetricsState {
        val file = archive.fileFor(station, name)
        if (!file.isFile) return FileMetricsState.Failed("File is not in the archive: $name")
        return try {
            val t0 = System.nanoTime()
            val pf = ParquetFile.open(file)
            val index = ParquetIndexer.index(pf)
            val md = pf.metadata
            val utcLeaf = md.leaf(ParquetIndexer.UTC)
            val groups = md.rowGroups.mapIndexed { g, rg ->
                val s = utcLeaf?.let { rg.columns[it.index].statistics }
                RowGroupSummary(g, rg.numRows, rg.totalByteSize, s?.minLong(ParquetType.INT64), s?.maxLong(ParquetType.INT64))
            }
            val columns = pf.leaves.map { leaf ->
                val col = pf.readColumn(leaf)
                val stats = md.rowGroups.firstOrNull()?.columns?.get(leaf.index)?.statistics?.takeIf { md.rowGroups.size == 1 && it.hasMinMax }
                summarize(leaf.name, leaf.type, leaf.isTimestampNanosUtc, col, stats?.let { s -> if (leaf.type == ParquetType.FLOAT) s.minFloat()?.toString() else s.minLong(leaf.type)?.toString() }, stats?.let { s -> if (leaf.type == ParquetType.FLOAT) s.maxFloat()?.toString() else s.maxLong(leaf.type)?.toString() })
            }
            val decodeMs = (System.nanoTime() - t0) / 1_000_000
            FileMetricsState.Loaded(
                station = station,
                name = name,
                sizeBytes = file.length(),
                index = index,
                codec = md.rowGroups.firstOrNull()?.columns?.firstOrNull()?.codec,
                footerBytes = md.footerLength,
                columnOrders = md.columnOrders.size,
                rowGroups = groups,
                columns = columns,
                metadata = md.keyValueMetadata,
                decodeMillis = decodeMs,
            )
        } catch (e: ParquetFormatException) {
            FileMetricsState.Failed("The reader refused this file: ${e.message}")
        } catch (e: java.io.IOException) {
            FileMetricsState.Failed("Could not read the file: ${e.message}")
        }
    }

    private fun summarize(name: String, type: ParquetType, timestamp: Boolean, col: ColumnValues, statsMin: String?, statsMax: String?): ColumnSummary {
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
                is ColumnValues.Binary -> Unit // strings / WKB: no numeric range
                else -> {
                    val v = col.asLong(i)!!
                    if (v < minL) minL = v
                    if (v > maxL) maxL = v
                }
            }
        }
        val (minText, maxText) = when {
            !any -> NO_VALUE to NO_VALUE
            col is ColumnValues.Float32 || col is ColumnValues.Float64 -> (if (minD.isFinite()) trim(minD) else NO_VALUE) to (if (maxD.isFinite()) trim(maxD) else NO_VALUE)
            col is ColumnValues.Binary -> NO_VALUE to NO_VALUE
            else -> minL.toString() to maxL.toString()
        }
        return ColumnSummary(name, type, timestamp, col.size, col.nullCount, minText, maxText, first ?: NO_VALUE, last ?: NO_VALUE, statsMin, statsMax)
    }

    private fun format(col: ColumnValues, i: Int): String = when (col) {
        is ColumnValues.Float32 -> trim(col.values[i].toDouble())
        is ColumnValues.Float64 -> trim(col.values[i])
        is ColumnValues.Int32 -> col.values[i].toString()
        is ColumnValues.Int64 -> col.values[i].toString()
        is ColumnValues.Binary -> if (col.length(i) <= 64) col.string(i) ?: "" else "${col.length(i)} bytes"
    }

    private fun trim(v: Double): String = if (v == Math.rint(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else "%.4g".format(java.util.Locale.ROOT, v)
}
