package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.data.history.parquet.ColumnValues
import earth.walkthru.aqsync.data.history.parquet.ParquetFile

/**
 * Time buckets the History screens read instead of raw rows. Sizes are chosen so that a year view
 * is ~365 points (DAY), a month ~720 (HOUR) and a week ~672 (MIN15); anything finer reads the rows.
 */
enum class Resolution(val seconds: Long) {
    MIN15(900), HOUR(3600), DAY(86_400);

    /** Rows the logger produces in one bucket when nothing is missed (one every 10 s). */
    val expectedRows: Int get() = (seconds / 10).toInt()

    fun bucketStart(epochSeconds: Long): Long = Math.floorDiv(epochSeconds, seconds) * seconds
}

/** One metric's aggregate inside one bucket. `n` are the rows that passed the metric's gate and were non-null. */
data class MetricAgg(val n: Int, val mean: Float, val min: Float, val max: Float)

/**
 * One aggregated bucket: the row counts that are the same for every metric, and one [MetricAgg] per
 * registered metric that had a value. `coverage(metric)` = its `n / expected`, so a bucket built from
 * three readings says so.
 */
data class Bucket(
    val station: String,
    val resolution: Resolution,
    val startEpochS: Long,
    /** Every timed row in the slot, whatever its validity. */
    val rows: Int,
    /** Of `rows`, those placed by a time estimate (§4.6). */
    val estimatedRows: Int,
    val metrics: Map<String, MetricAgg>,
) {
    val endEpochS: Long get() = startEpochS + resolution.seconds
    fun agg(metric: MetricSpec): MetricAgg? = metrics[metric.id]
    fun coverage(metric: MetricSpec): Float = ((metrics[metric.id]?.n ?: 0).toFloat() / resolution.expectedRows).coerceIn(0f, 1f)

    /** Rows behind the primary (PM2.5) mean — what the Now card and Home cards call "valid readings". */
    val validRows: Int get() = metrics[Metrics.primary.id]?.n ?: 0
}

/**
 * Pure aggregation of decoded columns into [Bucket]s for every metric in the registry. Feed it any
 * number of files (a UTC day's worth, in practice) through [add]; [buckets] returns one entry per
 * non-empty (resolution, start). Rows without UTC are skipped unless a [TimeEstimate.Anchor] dates
 * them (§4.6). Rows already seen under the same `(boot, sequence)` are skipped, like the daily file.
 */
class Aggregator(
    private val station: String,
    private val resolutions: Set<Resolution> = Resolution.entries.toSet(),
    private val metrics: List<MetricSpec> = Metrics.all,
) {
    private class Acc(metricCount: Int) {
        var rows = 0
        var estimated = 0
        val n = IntArray(metricCount)
        val sum = DoubleArray(metricCount)
        val min = FloatArray(metricCount) { Float.POSITIVE_INFINITY }
        val max = FloatArray(metricCount) { Float.NEGATIVE_INFINITY }
    }

    private val accs = HashMap<Pair<Resolution, Long>, Acc>()
    private val seen = HashSet<Triple<Long, Long, Long>>()
    private val gateColumns: List<String> = metrics.mapNotNull { it.gate?.column }.distinct()

    /** Adds every timed row of [file]; with an [anchor] for the file's boot, unsynced rows get an estimated time. */
    fun add(file: ParquetFile, anchor: TimeEstimate.Anchor? = null) {
        val md = file.metadata
        val utc = md.leaf(ParquetIndexer.UTC)?.let { file.readColumn(it) } ?: return
        fun col(name: String): ColumnValues? = md.leaf(name)?.let { file.readColumn(it) }
        val applicable = anchor?.takeIf { it.boot == md.bootId }
        val mono = if (applicable != null) col(ParquetIndexer.MONO) else null
        val bootHi = col("boot_id_hi")
        val bootLo = col("boot_id_lo")
        val seq = col(ParquetIndexer.SEQ)
        val values = metrics.map { col(it.column) }
        val gates = gateColumns.associateWith { col(it) }
        for (i in 0 until utc.size) {
            var estimated = false
            val ns = utc.asLong(i) ?: run {
                val m = mono?.asLong(i) ?: return@run null
                estimated = true
                applicable?.estimateNs(m)
            } ?: continue
            if (seq != null) {
                val key = Triple(bootHi?.asLong(i) ?: 0L, bootLo?.asLong(i) ?: 0L, seq.asLong(i) ?: -1L)
                if (key.third >= 0 && !seen.add(key)) continue
            }
            val s = Math.floorDiv(ns, 1_000_000_000L)
            for (r in resolutions) {
                val acc = accs.getOrPut(r to r.bucketStart(s)) { Acc(metrics.size) }
                acc.rows++
                if (estimated) acc.estimated++
                for ((k, spec) in metrics.withIndex()) {
                    val colv = values[k] ?: continue
                    if (!spec.accepts(spec.gate?.let { g -> gates[g.column]?.asLong(i) })) continue
                    val v = colv.asDouble(i) ?: continue
                    if (v.isNaN() || v.isInfinite()) continue
                    acc.n[k]++
                    acc.sum[k] += v
                    val f = v.toFloat()
                    if (f < acc.min[k]) acc.min[k] = f
                    if (f > acc.max[k]) acc.max[k] = f
                }
            }
        }
    }

    fun buckets(): List<Bucket> = accs.entries
        .sortedWith(compareBy({ it.key.first.ordinal }, { it.key.second }))
        .map { (key, a) ->
            val per = LinkedHashMap<String, MetricAgg>()
            for ((k, spec) in metrics.withIndex()) {
                if (a.n[k] > 0) per[spec.id] = MetricAgg(a.n[k], (a.sum[k] / a.n[k]).toFloat(), a.min[k], a.max[k])
            }
            Bucket(station, key.first, key.second, a.rows, a.estimated, per)
        }
}
