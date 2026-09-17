package earth.walkthru.aqsync.data.history

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/**
 * One drawn column: a fixed time slot with the weighted mean of what fell into it, the peak inside
 * it, how much of the slot has readings, and how much of it sits on estimated time.
 */
data class HistoryColumn(
    val startS: Long,
    val endS: Long,
    val mean: Float?,
    val max: Float?,
    /** 0–1, from the points' own coverage weighted by their span. */
    val coverage: Float,
    /** 0–1 share of covered time that is on estimated time (§4.6). */
    val estimatedShare: Float,
) {
    val estimated: Boolean get() = estimatedShare >= 0.5f
}

/** Pure re-binning and axis choices for the History chart; shared by every range. */
object Columns {

    /** Column width per range: 96 · 168 · 30 · 52 columns. */
    fun slotSeconds(range: HistoryRange): Long = when (range) {
        HistoryRange.DAY -> 900L
        HistoryRange.WEEK -> 3_600L
        HistoryRange.MONTH -> 86_400L
        HistoryRange.YEAR -> 7 * 86_400L
    }

    /**
     * Folds [series] into fixed slots of [slotS] seconds aligned to [alignS] (a UTC or local midnight).
     * Means are weighted by each point's covered seconds (`coverage × span`), never averages of
     * averages; a slot with no covered time is a gap (`mean == null`) but still returned, so the
     * chart keeps its rhythm.
     */
    fun rebin(series: HistorySeries, slotS: Long, alignS: Long = 0L): List<HistoryColumn> {
        val first = floorTo(series.fromS, slotS, alignS)
        val count = ((series.toS - first + slotS - 1) / slotS).toInt().coerceAtLeast(0)
        val weight = DoubleArray(count)
        val sum = DoubleArray(count)
        val peak = FloatArray(count) { Float.NEGATIVE_INFINITY }
        val covered = DoubleArray(count)
        val estimated = DoubleArray(count)
        for (p in series.points) {
            val idx = ((p.startS - first) / slotS).toInt()
            if (idx < 0 || idx >= count) continue
            val span = (p.endS - p.startS).toDouble()
            val w = span * p.coverage
            covered[idx] += w
            if (p.estimated) estimated[idx] += w
            val m = p.mean ?: continue
            if (w <= 0) continue
            sum[idx] += m * w
            weight[idx] += w
            val hi = p.max ?: m
            if (hi > peak[idx]) peak[idx] = hi
        }
        return List(count) { i ->
            val start = first + i * slotS
            val slotSpan = slotS.toDouble()
            HistoryColumn(
                startS = start,
                endS = start + slotS,
                mean = if (weight[i] > 0) (sum[i] / weight[i]).toFloat() else null,
                max = if (peak[i].isFinite()) peak[i] else null,
                coverage = (covered[i] / slotSpan).toFloat().coerceIn(0f, 1f),
                estimatedShare = if (covered[i] > 0) (estimated[i] / covered[i]).toFloat().coerceIn(0f, 1f) else 0f,
            )
        }
    }

    private fun floorTo(t: Long, step: Long, align: Long): Long = Math.floorDiv(t - align, step) * step + align

    /**
     * The y-axis top: the value most columns live under, not the tallest spike. Takes the 95th
     * percentile of column means (a full day of 15 µg/m³ with one 379 spike stays readable at 0–40),
     * at least [floor], rounded up to a nice step. Columns above it are clipped and get a peak cap.
     */
    fun axisMax(columns: List<HistoryColumn>, floor: Float): Float {
        val means = columns.mapNotNull { it.mean }.sorted()
        val p95 = if (means.isEmpty()) 0f else means[((means.size - 1) * 0.95).toInt()]
        val raw = max(p95 * 1.2f, floor)
        val step = niceStep(raw)
        return ceil(raw / step) * step
    }

    /** Grid step giving ~4 lines: 1 · 2 · 5 × 10ⁿ. */
    fun niceStep(range: Float): Float {
        if (range <= 0f) return 1f
        val rough = range / 4f
        val mag = 10.0.pow(floor(log10(rough.toDouble()))).toFloat()
        val n = rough / mag
        return mag * when {
            n <= 1f -> 1f
            n <= 2f -> 2f
            n <= 5f -> 5f
            else -> 10f
        }
    }
}
