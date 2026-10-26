package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.data.history.parquet.ColumnValues
import earth.walkthru.aqsync.data.history.parquet.ParquetFile
import space.opensensor.model.Aggregate
import space.opensensor.model.ValidityGate

/**
 * Time buckets the History screens read instead of raw rows. Sizes are chosen so that a year view
 * is ~365 points (DAY), a month ~720 (HOUR) and a week ~672 (MIN15); anything finer reads the rows.
 */
enum class Resolution(val seconds: Long) {
    MIN15(900), HOUR(3600), DAY(86_400);

    /** Rows a logger writing every [sampleIntervalMs] produces in one bucket, 0 when the interval is unknown. */
    fun expectedRows(sampleIntervalMs: Long): Long = if (sampleIntervalMs > 0) seconds * MS_PER_S / sampleIntervalMs else 0L

    fun bucketStart(epochSeconds: Long): Long = epochSeconds.floorDiv(seconds) * seconds

    private companion object {
        const val MS_PER_S = 1000L
    }
}

/**
 * One metric's rollup inside one bucket. `n` are the rows that passed the metric's gate and were
 * non-null, its valid rows. `settling` of those were within the metric's `settleSeconds` after power
 * on (counted, not excluded). [mean] is the metric's aggregate of those rows (the mean of a `mean`
 * metric, the last row of a `point` one, the total of a `sum` one), [min] and [max] their extremes.
 */
data class MetricAgg(val n: Int, val mean: Float, val min: Float, val max: Float, val settling: Int = 0)

/**
 * One aggregated bucket: the row counts that are the same for every metric, and one [MetricAgg] per
 * metric that had a value. [sampleIntervalMs] is the shortest footer interval of its files, 0 when
 * none said, so `coverage(metric)` = its valid rows over the rows that interval allows.
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
    val sampleIntervalMs: Long = 0,
) {
    val endEpochS: Long get() = startEpochS + resolution.seconds
    val expectedRows: Long get() = resolution.expectedRows(sampleIntervalMs)
    fun agg(metric: MetricSpec): MetricAgg? = metrics[metric.id]
    fun coverage(metric: MetricSpec): Float =
        if (expectedRows <= 0) 0f else ((metrics[metric.id]?.n ?: 0).toFloat() / expectedRows).coerceIn(0f, 1f)
}

/**
 * Pure aggregation of decoded columns into [Bucket]s for each of [metrics]. Feed it any number of
 * files (a UTC day's worth, in practice) through [add]; [buckets] returns one entry per non-empty
 * (resolution, start). Rows without UTC are skipped unless a [TimeEstimate.Anchor] dates them (§4.6).
 * A row already seen under the same [RowKey] is skipped, as in the daily file and the day chart.
 *
 * Each file is read by its own board template ([templates], from its footer `dictionary_sha256`). A
 * metric takes its gate and settle time from that template, and a file whose template lacks the metric
 * or stores it in another unit gives it no values, so units never mix in one bucket (rule 9). A file
 * no template resolves still counts its rows. Each metric rolls up with its own aggregate.
 *
 * Accumulators live in primitive arrays, one slot per (resolution, bucket start), found through a
 * per-resolution `Long` to slot map with a one-entry cache, since rows arrive in time order (#63).
 * [DayPass] feeds it rows it has already timed and de-duplicated, without a second decode.
 */
class Aggregator(
    private val station: String,
    resolutions: Set<Resolution> = Resolution.entries.toSet(),
    private val metrics: List<MetricSpec> = Metrics.all,
    private val templates: FileTemplates = FileTemplates.BUNDLED,
) {
    private val res: Array<Resolution> = resolutions.toTypedArray()
    private val m = metrics.size
    private val rollups: Array<Aggregate> = Array(m) { metrics[it].aggregate }

    private var capacity = 16
    private var slots = 0
    private var slotRes = IntArray(capacity)
    private var slotStart = LongArray(capacity)
    private var slotInterval = LongArray(capacity)
    private var rows = IntArray(capacity)
    private var estimated = IntArray(capacity)
    private var n = IntArray(capacity * m)
    private var settling = IntArray(capacity * m)
    private var sum = DoubleArray(capacity * m)
    private var min = FloatArray(capacity * m) { Float.POSITIVE_INFINITY }
    private var max = FloatArray(capacity * m) { Float.NEGATIVE_INFINITY }
    private var lastNs = LongArray(capacity * m) { Long.MIN_VALUE }
    private var last = FloatArray(capacity * m)
    private val modes = HashMap<Int, HashMap<Float, Int>>()
    private val slotOf = Array(res.size) { LongIntMap() }
    private val lastStart = LongArray(res.size) { Long.MIN_VALUE }
    private val lastSlot = IntArray(res.size)
    private val seen = RowKeys()

    /** The columns one file gives the aggregator, unboxed, with that file's gates and settle times. */
    internal class Columns(
        val mono: LongColumn?,
        /** Per metric, null when this file gives it no values. */
        val values: Array<DoubleColumn?>,
        /** Per metric, null when every non-null value counts. */
        val gates: Array<GateColumn?>,
        val settleUs: LongArray,
        val sampleIntervalMs: Long,
    )

    /**
     * Reads what [metrics] need through [column], which returns null for a column the file lacks, under
     * the template of the file's footer [keyValues]. A humidity-corrected metric gets its values
     * corrected here, row by row, so every rollup the tiers keep is of corrected rows.
     */
    internal fun columns(column: (String) -> ColumnValues?, keyValues: Map<String, String>): Columns {
        val file = templates.of(keyValues)
        val bound = file.bind(metrics)
        val doubles = HashMap<String, DoubleColumn?>()
        fun double(name: String) = doubles.getOrPut(name) { column(name)?.let(DoubleColumn::of) }
        val gateCache = HashMap<ValidityGate, GateColumn>()
        return Columns(
            mono = column(ParquetIndexer.MONO)?.let(LongColumn::of),
            values = Array(m) { k ->
                val spec = bound[k] ?: return@Array null
                val stored = double(spec.column)
                val rh = spec.humidityColumn
                if (rh == null || stored == null) stored else stored.correctedBy(spec, double(rh))
            },
            gates = Array(m) { k -> bound[k]?.gate?.let { g -> gateCache.getOrPut(g) { GateColumn(g, column(g.column)) } } },
            settleUs = LongArray(m) { k -> bound[k]?.let { (it.settleSeconds * US_PER_S).toLong() } ?: 0L },
            sampleIntervalMs = file.sampleIntervalMs,
        )
    }

    /** Adds every timed row of [file]; with an [anchor] for the file's boot, unsynced rows get an estimated time. */
    fun add(file: ParquetFile, anchor: TimeEstimate.Anchor? = null) {
        val md = file.metadata
        fun col(name: String): ColumnValues? = md.leaf(name)?.let { file.readColumn(it) }
        val utc = UtcColumns.readEventNs(file)?.let(LongColumn::of) ?: return
        val applicable = anchor?.takeIf { it.boot == md.bootId }
        val columns = columns(::col, md.keyValueMetadata)
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
        val interval = columns.sampleIntervalMs
        for (r in res.indices) {
            val slot = slot(r, res[r].bucketStart(s))
            rows[slot]++
            if (isEstimate) estimated[slot]++
            if (interval > 0 && (slotInterval[slot] == 0L || interval < slotInterval[slot])) slotInterval[slot] = interval
            val base = slot * m
            for (k in 0 until m) {
                val colv = columns.values[k] ?: continue
                val gate = columns.gates[k]
                if (gate != null && !gate.passes(i)) continue
                if (!colv.has(i)) continue
                val v = colv.values[i]
                if (v.isNaN() || v.isInfinite()) continue
                val at = base + k
                n[at]++
                val settle = columns.settleUs[k]
                if (settle > 0 && monoUs < settle) settling[at]++
                sum[at] += v
                val f = v.toFloat()
                if (f < min[at]) min[at] = f
                if (f > max[at]) max[at] = f
                if (ns >= lastNs[at]) {
                    lastNs[at] = ns
                    last[at] = f
                }
                if (rollups[k] == Aggregate.MODE) modes.getOrPut(at) { HashMap() }.let { it[f] = (it[f] ?: 0) + 1 }
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
        slotInterval = slotInterval.copyOf(c)
        rows = rows.copyOf(c)
        estimated = estimated.copyOf(c)
        n = n.copyOf(c * m)
        settling = settling.copyOf(c * m)
        sum = sum.copyOf(c * m)
        min = min.copyOf(c * m).also { it.fill(Float.POSITIVE_INFINITY, capacity * m) }
        max = max.copyOf(c * m).also { it.fill(Float.NEGATIVE_INFINITY, capacity * m) }
        lastNs = lastNs.copyOf(c * m).also { it.fill(Long.MIN_VALUE, capacity * m) }
        last = last.copyOf(c * m)
        capacity = c
    }

    /** The rolled up value of metric [k] at accumulator [at], which holds at least one row. */
    private fun rolled(k: Int, at: Int): Float = when (rollups[k]) {
        Aggregate.MEAN -> (sum[at] / n[at]).toFloat()
        Aggregate.SUM -> sum[at].toFloat()
        Aggregate.MINIMUM -> min[at]
        Aggregate.MAXIMUM -> max[at]
        Aggregate.MODE -> modes[at]?.let(::modeOf) ?: last[at]
        else -> last[at]
    }

    fun buckets(): List<Bucket> = (0 until slots)
        .sortedWith(compareBy({ res[slotRes[it]].ordinal }, { slotStart[it] }))
        .map { slot ->
            val per = LinkedHashMap<String, MetricAgg>()
            val base = slot * m
            for (k in 0 until m) {
                val at = base + k
                if (n[at] > 0) per[metrics[k].id] = MetricAgg(n[at], rolled(k, at), min[at], max[at], settling[at])
            }
            Bucket(station, res[slotRes[slot]], slotStart[slot], rows[slot], estimated[slot], per, slotInterval[slot])
        }

    private companion object {
        const val US_PER_S = 1_000_000.0
    }
}

/** The most frequent of [counts], the smallest value on a tie so the result never depends on order. */
internal fun modeOf(counts: Map<Float, Int>): Float? =
    counts.entries.maxWithOrNull(compareBy<Map.Entry<Float, Int>> { it.value }.thenByDescending { it.key })?.key

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
