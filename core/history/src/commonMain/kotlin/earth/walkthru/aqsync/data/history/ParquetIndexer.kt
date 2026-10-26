package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.data.history.parquet.ColumnValues
import earth.walkthru.aqsync.data.history.parquet.LeafColumn
import earth.walkthru.aqsync.data.history.parquet.ParquetFile
import earth.walkthru.aqsync.data.history.parquet.ParquetFileMetadata
import earth.walkthru.aqsync.data.history.parquet.ParquetType
import kotlin.coroutines.cancellation.CancellationException

/**
 * What the archive index remembers about one verified file so that History never re-opens files it
 * does not need: the row count, the time span (UTC where the rows have it, monotonic always), the
 * sequence range and how many rows hold a valid reading of the file's headline metric.
 *
 * Every field is derived from the file itself, never from its name: the Hive path says which UTC
 * window a file *belongs to*, the rows say what it *contains* (a boot mid-window, a `FLUSH`, or a
 * clock correction make them differ).
 */
data class ParquetIndex(
    val rows: Long,
    val rowGroups: Int,
    val columns: Int,
    /** Footer `firmware.schema`: for example `cores3-telemetry-v5` or `waveshare-sim7670g-telemetry-v4`, null on a foreign writer. */
    val schemaVersion: String?,
    val createdBy: String?,
    val bootId: String?,
    /** Footer `file.purpose`: `telemetry`, or `codec-comparison-duplicate-rows` for benchmark copies that must stay out of History. */
    val purpose: String?,
    /** Rows whose event time (`event_time_utc_us`, or `event_time_utc_ns` on older files) is set, and its range in nanoseconds. */
    val utcRows: Long,
    val firstUtcNs: Long?,
    val lastUtcNs: Long?,
    val firstMonoUs: Long?,
    val lastMonoUs: Long?,
    val firstSeq: Long?,
    val lastSeq: Long?,
    /**
     * Rows that pass the gate of the file template's headline metric (the first of its profile's
     * primary list) and hold a finite value. Null when no template resolves the file's dictionary, or
     * when that column fails to read. A damaged page then costs the count, never the file's index.
     */
    val validRows: Long?,
    /** True when the UTC span came from footer statistics without decoding (v6 writer). */
    val fromStatistics: Boolean,
) {
    val hasUtc: Boolean get() = utcRows > 0
    val isTelemetry: Boolean get() = purpose == null || purpose == "telemetry"
}

/** Builds a [ParquetIndex]. Pure: no Android, no I/O beyond the opened file. */
object ParquetIndexer {

    /** The current event time column. Read it through [UtcColumns], which also finds the older name. */
    const val UTC = UtcColumns.EVENT_US
    const val MONO = "monotonic_us"
    const val SEQ = "sequence"

    fun index(file: ParquetFile, templates: FileTemplates = FileTemplates.BUNDLED): ParquetIndex {
        val md = file.metadata
        val utcLeaf = UtcColumns.eventLeaf(md)
        val utcScale = utcLeaf?.let(UtcColumns::nanosPerUnit)
        val monoLeaf = md.leaf(MONO)
        val seqLeaf = md.leaf(SEQ)

        // Fast path: v6 footers carry min/max + null_count per row group for every column.
        val utcStats = utcLeaf?.let { rangeFromStatistics(file, it) }?.scaled(utcScale!!)
        val monoStats = monoLeaf?.let { rangeFromStatistics(file, it) }
        val seqStats = seqLeaf?.let { rangeFromStatistics(file, it) }
        val fromStats = utcStats != null && monoStats != null && seqStats != null

        val utc = utcStats ?: utcLeaf?.let { rangeByDecoding(UtcColumns.readNs(file, it)) }
        val mono = monoStats ?: monoLeaf?.let { rangeByDecoding(file.readColumn(it)) }
        val seq = seqStats ?: seqLeaf?.let { rangeByDecoding(file.readColumn(it)) }
        val footer = md.footer
        val headline = templates.of(footer).template?.let { t ->
            Metrics.of(t).firstOrNull { it.primary && it.humidityColumn == null }
        }
        val validRows = headline?.let { metric ->
            try {
                validRows(file, metric)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }

        return ParquetIndex(
            rows = md.numRows,
            rowGroups = md.rowGroups.size,
            columns = md.leaves.size,
            schemaVersion = footer?.schema,
            createdBy = md.createdBy,
            bootId = footer?.bootId,
            purpose = footer?.purpose,
            utcRows = utc?.count ?: 0L,
            firstUtcNs = utc?.min,
            lastUtcNs = utc?.max,
            firstMonoUs = mono?.min,
            lastMonoUs = mono?.max,
            firstSeq = seq?.min,
            lastSeq = seq?.max,
            validRows = validRows,
            fromStatistics = fromStats,
        )
    }

    /** Rows of [file] that pass [metric]'s gate and hold a finite value of its column. */
    private fun validRows(file: ParquetFile, metric: MetricSpec): Long {
        val md = file.metadata
        val values = md.leaf(metric.column)?.let { DoubleColumn.of(file.readColumn(it)) } ?: return 0L
        val gate = metric.gate?.let { g -> GateColumn(g, md.leaf(g.column)?.let { file.readColumn(it) }) }
        var n = 0L
        for (i in 0 until values.size) {
            if (gate != null && !gate.passes(i)) continue
            if (values.has(i) && values.values[i].isFinite()) n++
        }
        return n
    }

    /** Non-null count and min/max of an integer column. `min`/`max` are null when every value is null. */
    data class Range(val count: Long, val min: Long?, val max: Long?) {
        /** This range with [min] and [max] multiplied by [scale], or null when either would overflow. */
        fun scaled(scale: Long): Range? {
            if (scale == 1L) return this
            val limit = Long.MAX_VALUE / scale
            if ((min != null && (min > limit || min < -limit)) || (max != null && (max > limit || max < -limit))) return null
            return Range(count, min?.times(scale), max?.times(scale))
        }
    }

    /**
     * From footer statistics only, or null when any row group lacks `null_count` or — for a group
     * with defined values — `min_value`/`max_value`. Never guesses: a partial answer is no answer.
     */
    fun rangeFromStatistics(file: ParquetFile, leaf: LeafColumn): Range? = rangeFromStatistics(file.metadata, leaf)

    /** The same, from the footer alone. A negative null count or a min above its max is no answer either. */
    fun rangeFromStatistics(md: ParquetFileMetadata, leaf: LeafColumn): Range? {
        if (leaf.type != ParquetType.INT32 && leaf.type != ParquetType.INT64) return null
        val groups = md.rowGroups
        if (groups.isEmpty()) return Range(0, null, null)
        var count = 0L
        var min: Long? = null
        var max: Long? = null
        for (rg in groups) {
            val s = rg.columns[leaf.index].statistics ?: return null
            val nulls = s.nullCount ?: return null
            // A negative null count would make `defined` larger than the row group. Decode instead.
            if (nulls < 0) return null
            val defined = rg.numRows - nulls
            if (defined < 0) return null
            count += defined
            if (defined == 0L) continue
            val lo = s.minLong(leaf.type) ?: return null
            val hi = s.maxLong(leaf.type) ?: return null
            // Statistics that contradict themselves are not trusted for a time span.
            if (lo > hi) return null
            min = if (min == null) lo else minOf(min, lo)
            max = if (max == null) hi else maxOf(max, hi)
        }
        return Range(count, min, max)
    }

    fun rangeByDecoding(col: ColumnValues): Range {
        var count = 0L
        var min = Long.MAX_VALUE
        var max = Long.MIN_VALUE
        for (i in 0 until col.size) {
            val v = col.asLong(i) ?: continue
            count++
            if (v < min) min = v
            if (v > max) max = v
        }
        return if (count == 0L) Range(0, null, null) else Range(count, min, max)
    }
}
