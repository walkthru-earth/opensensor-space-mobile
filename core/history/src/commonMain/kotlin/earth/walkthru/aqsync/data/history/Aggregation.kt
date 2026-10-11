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

    fun bucketStart(epochSeconds: Long): Long = epochSeconds.floorDiv(seconds) * seconds
}

/**
 * One metric's aggregate inside one bucket. `n` are the rows that passed the metric's gate and were
 * non-null; `settling` of those were within the metric's `settleSeconds` after power-on (counted, not
 * excluded — the drift is small on this hardware, see `MetricSpec.settleSeconds`).
 */
data class MetricAgg(val n: Int, val mean: Float, val min: Float, val max: Float, val settling: Int = 0)

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
 * them (§4.6). A row already seen under the same [RowKey] is skipped, as in the daily file and the day chart.
 *
 * Accumulators live in primitive arrays, one slot per (resolution, bucket start), found through a
 * per-resolution `Long` to slot map with a one-entry cache, since rows arrive in time order (#63).
 * [DayPass] feeds it rows it has already timed and de-duplicated, without a second decode.
 */
class Aggregator(
    private val station: String,
    resolutions: Set<Resolution> = Resolution.entries.toSet(),
    private val metrics: List<MetricSpec> = Metrics.all,
) {
    private val res: Array<Resolution> = resolutions.toTypedArray()
    private val m = metrics.size
    private val gates: Array<Gate?> = Array(m) { metrics[it].gate }
    private val settleUs = LongArray(m) { metrics[it].settleSeconds * 1_000_000L }

    private var capacity = 16
    private var slots = 0
    private var slotRes = IntArray(capacity)
    private var slotStart = LongArray(capacity)
    private var rows = IntArray(capacity)
    private var estimated = IntArray(capacity)
    private var n = IntArray(capacity * m)
    private var settling = IntArray(capacity * m)
    private var sum = DoubleArray(capacity * m)
    private var min = FloatArray(capacity * m) { Float.POSITIVE_INFINITY }
    private var max = FloatArray(capacity * m) { Float.NEGATIVE_INFINITY }
    private val slotOf = Array(res.size) { LongIntMap() }
    private val lastStart = LongArray(res.size) { Long.MIN_VALUE }
    private val lastSlot = IntArray(res.size)
    private val seen = RowKeys()

    /** The columns one file gives the aggregator, unboxed. */
    internal class Columns(
        val mono: LongColumn?,
        val values: Array<DoubleColumn?>,
        val gates: Array<LongColumn?>,
    )

    /** Reads what [metrics] need through [column], which returns null for a column the file lacks. */
    internal fun columns(column: (String) -> ColumnValues?): Columns {
        val cache = HashMap<String, LongColumn?>()
        return Columns(
            mono = column(ParquetIndexer.MONO)?.let(LongColumn::of),
            values = Array(m) { k -> column(metrics[k].column)?.let(DoubleColumn::of) },
            gates = Array(m) { k -> gates[k]?.let { g -> cache.getOrPut(g.column) { column(g.column)?.let(LongColumn::of) } } },
        )
    }

    /** Adds every timed row of [file]; with an [anchor] for the file's boot, unsynced rows get an estimated time. */
    fun add(file: ParquetFile, anchor: TimeEstimate.Anchor? = null) {
        val md = file.metadata
        fun col(name: String): ColumnValues? = md.leaf(name)?.let { file.readColumn(it) }
        val utc = col(ParquetIndexer.UTC)?.let(LongColumn::of) ?: return
        val applicable = anchor?.takeIf { it.boot == md.bootId }
        val columns = columns(::col)
        val mono = if (applicable != null) columns.mono else null
        val bootHi = col("boot_id_hi")?.let(LongColumn::of)
        val bootLo = col("boot_id_lo")?.let(LongColumn::of)
        val seq = col(ParquetIndexer.SEQ)?.let(LongColumn::of)
        for (i in 0 until utc.size) {
            var isEstimate = false
            val ns = if (utc.has(i)) {
                utc.values[i]
            } else {
                if (mono == null || !mono.has(i)) continue
                isEstimate = true
                applicable!!.estimateNsOrNull(mono.values[i]) ?: continue
            }
            if (!seen.firstSight(bootHi.at(i), bootLo.at(i), seq != null && seq.has(i), if (seq != null && seq.has(i)) seq.values[i] else ns)) continue
            accumulate(columns, i, ns, isEstimate)
        }
    }

    /** Adds row [i] at timeline [ns]. The caller has timed and de-duplicated it. */
    internal fun accumulate(columns: Columns, i: Int, ns: Long, isEstimate: Boolean) {
        val s = ns.floorDiv(1_000_000_000L)
        val mono = columns.mono
        val monoUs = if (mono != null && mono.has(i)) mono.values[i] else Long.MAX_VALUE
        for (r in res.indices) {
            val slot = slot(r, res[r].bucketStart(s))
            rows[slot]++
            if (isEstimate) estimated[slot]++
            val base = slot * m
            for (k in 0 until m) {
                val colv = columns.values[k] ?: continue
                val gate = gates[k]
                if (gate != null) {
                    val g = columns.gates[k]
                    if (g == null || !g.has(i) || g.values[i] != gate.value) continue
                }
                if (!colv.has(i)) continue
                val v = colv.values[i]
                if (v.isNaN() || v.isInfinite()) continue
                val at = base + k
                n[at]++
                if (settleUs[k] > 0 && monoUs < settleUs[k]) settling[at]++
                sum[at] += v
                val f = v.toFloat()
                if (f < min[at]) min[at] = f
                if (f > max[at]) max[at] = f
            }
        }
    }

    private fun slot(r: Int, start: Long): Int {
        if (lastStart[r] == start) return lastSlot[r]
        val map = slotOf[r]
        var slot = map.get(start)
        if (slot < 0) {
            if (slots == capacity) grow()
            slot = slots++
            slotRes[slot] = r
            slotStart[slot] = start
            map.put(start, slot)
        }
        lastStart[r] = start
        lastSlot[r] = slot
        return slot
    }

    private fun grow() {
        val c = capacity * 2
        slotRes = slotRes.copyOf(c)
        slotStart = slotStart.copyOf(c)
        rows = rows.copyOf(c)
        estimated = estimated.copyOf(c)
        n = n.copyOf(c * m)
        settling = settling.copyOf(c * m)
        sum = sum.copyOf(c * m)
        min = min.copyOf(c * m).also { it.fill(Float.POSITIVE_INFINITY, capacity * m) }
        max = max.copyOf(c * m).also { it.fill(Float.NEGATIVE_INFINITY, capacity * m) }
        capacity = c
    }

    fun buckets(): List<Bucket> = (0 until slots)
        .sortedWith(compareBy({ res[slotRes[it]].ordinal }, { slotStart[it] }))
        .map { slot ->
            val per = LinkedHashMap<String, MetricAgg>()
            val base = slot * m
            for (k in 0 until m) {
                val at = base + k
                if (n[at] > 0) per[metrics[k].id] = MetricAgg(n[at], (sum[at] / n[at]).toFloat(), min[at], max[at], settling[at])
            }
            Bucket(station, res[slotRes[slot]], slotStart[slot], rows[slot], estimated[slot], per)
        }
}

/** An open-addressing `Long` to non-negative `Int` map, -1 for a missing key. */
internal class LongIntMap {
    private var keys = LongArray(16)
    private var values = IntArray(16) { -1 }
    private var size = 0

    fun get(key: Long): Int {
        val mask = keys.size - 1
        var i = spread(key) and mask
        while (values[i] >= 0) {
            if (keys[i] == key) return values[i]
            i = (i + 1) and mask
        }
        return -1
    }

    fun put(key: Long, value: Int) {
        require(value >= 0)
        if ((size + 1) * 2 > keys.size) rehash()
        val mask = keys.size - 1
        var i = spread(key) and mask
        while (values[i] >= 0) {
            if (keys[i] == key) { values[i] = value; return }
            i = (i + 1) and mask
        }
        keys[i] = key
        values[i] = value
        size++
    }

    private fun rehash() {
        val oldKeys = keys
        val oldValues = values
        keys = LongArray(oldKeys.size * 2)
        values = IntArray(oldKeys.size * 2) { -1 }
        size = 0
        for (j in oldKeys.indices) if (oldValues[j] >= 0) put(oldKeys[j], oldValues[j])
    }

    private fun spread(key: Long): Int {
        val h = key * -0x61c8864680b583ebL
        return (h xor (h ushr 29)).toInt()
    }
}
