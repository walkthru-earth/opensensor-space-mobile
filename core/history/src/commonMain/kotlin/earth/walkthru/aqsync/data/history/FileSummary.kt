package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.data.history.parquet.ColumnValues
import earth.walkthru.aqsync.data.history.parquet.ParquetFile

/**
 * One archived Parquet file folded into one chart column — the "chart by file" view on the Files
 * screen, a diagnostic the time-binned History hides: a 3-row FLUSH file, a file placed by a time
 * estimate, a window that overlaps its neighbour, a file whose rows are all warm-up.
 *
 * Every row counts, timed or not — an unsynced file with no anchor still has readings — so [rows]
 * is the whole file; [timedRows] / [estimatedRows] say how many of them sit on the timeline and
 * [firstS]/[lastS] is that span (real UTC, else the estimate, else null). Aggregates are over every
 * registered metric, like [Aggregator], with the same gates.
 */
data class FileSummary(
    val name: String,
    val rows: Int,
    /** Rows with a real `event_time_utc_ns`. */
    val timedRows: Int,
    /** Rows without UTC that an anchor dated (§4.6); 0 when there is no anchor. */
    val estimatedRows: Int,
    /** Timeline span in epoch seconds — real UTC when the file has any, else the estimate, else null. */
    val firstS: Long?,
    val lastS: Long?,
    val metrics: Map<String, MetricAgg>,
) {
    fun agg(metric: MetricSpec): MetricAgg? = metrics[metric.id]

    /** The file has no place on the timeline at all (no UTC, no anchor). */
    val untimed: Boolean get() = timedRows == 0 && estimatedRows == 0

    /**
     * As one drawn column at slot [index] (the strip's x-axis is the file index, not time, so a 3-row
     * file is as visible as a full window): mean and peak of [metric], coverage = share of the file's
     * rows that passed the gate (a warm-up file is faint), estimated when it sits on estimated time.
     */
    fun column(index: Int, metric: MetricSpec): HistoryColumn {
        val a = metrics[metric.id]
        return HistoryColumn(
            startS = index.toLong(),
            endS = index + 1L,
            mean = a?.mean,
            max = if (metric.envelope) a?.max else null,
            coverage = if (rows == 0) 0f else ((a?.n ?: 0).toFloat() / rows).coerceIn(0f, 1f),
            estimatedShare = if (timedRows == 0 && estimatedRows > 0) 1f else 0f,
        )
    }

    companion object {
        /** Folds [file]; with an [anchor] for the file's boot, rows without UTC get an estimated time. */
        fun of(name: String, file: ParquetFile, anchor: TimeEstimate.Anchor? = null, metrics: List<MetricSpec> = Metrics.all): FileSummary {
            val md = file.metadata
            fun col(colName: String): ColumnValues? = md.leaf(colName)?.let { file.readColumn(it) }
            val rows = file.numRows.toInt()
            val utc = col(ParquetIndexer.UTC)
            val applicable = anchor?.takeIf { it.boot == md.bootId }
            val mono = if (applicable != null) col(ParquetIndexer.MONO) else null
            var timed = 0
            var estimated = 0
            var first = Long.MAX_VALUE
            var last = Long.MIN_VALUE
            var estFirst = Long.MAX_VALUE
            var estLast = Long.MIN_VALUE
            for (i in 0 until rows) {
                val ns = utc?.asLong(i)
                if (ns != null) {
                    timed++
                    if (ns < first) first = ns
                    if (ns > last) last = ns
                } else {
                    val m = mono?.asLong(i) ?: continue
                    val e = applicable!!.estimateNsOrNull(m) ?: continue
                    estimated++
                    if (e < estFirst) estFirst = e
                    if (e > estLast) estLast = e
                }
            }
            val values = metrics.map { col(it.column) }
            val gates = metrics.mapNotNull { it.gate?.column }.distinct().associateWith { col(it) }
            val per = LinkedHashMap<String, MetricAgg>()
            for ((k, spec) in metrics.withIndex()) {
                val colv = values[k] ?: continue
                var n = 0
                var sum = 0.0
                var min = Float.POSITIVE_INFINITY
                var max = Float.NEGATIVE_INFINITY
                for (i in 0 until rows) {
                    if (!spec.accepts(spec.gate?.let { g -> gates[g.column]?.asLong(i) })) continue
                    val v = colv.asDouble(i) ?: continue
                    if (v.isNaN() || v.isInfinite()) continue
                    n++
                    sum += v
                    val f = v.toFloat()
                    if (f < min) min = f
                    if (f > max) max = f
                }
                if (n > 0) per[spec.id] = MetricAgg(n, (sum / n).toFloat(), min, max)
            }
            val (spanFirst, spanLast) = when {
                timed > 0 -> first to last
                estimated > 0 -> estFirst to estLast
                else -> null to null
            }
            return FileSummary(
                name = name,
                rows = rows,
                timedRows = timed,
                estimatedRows = estimated,
                firstS = spanFirst?.let { it.floorDiv(1_000_000_000L) },
                lastS = spanLast?.let { it.floorDiv(1_000_000_000L) },
                metrics = per,
            )
        }
    }
}
