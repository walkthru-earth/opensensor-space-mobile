package earth.walkthru.aqsync.data.history

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
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

    /** Elapsed hours inside the exact local window: 23 or 25 columns on DST change days. */
    fun rebinHours(series: HistorySeries): List<HistoryColumn> {
        val boundaries = ArrayList<Long>()
        var at = series.fromS
        while (at < series.toS) {
            boundaries += at
            at += 3_600
        }
        boundaries += series.toS
        return rebinBoundaries(series, boundaries)
    }

    /** One variable-length local day per cell, including 23/25-hour days. */
    fun rebinLocalDays(series: HistorySeries, zone: TimeZone): List<HistoryColumn> {
        val boundaries = ArrayList<Long>()
        var day = localDateOf(series.fromS, zone)
        val end = localDateOf(series.toS, zone)
        while (day <= end) {
            boundaries += startOfDay(day, zone)
            day = day.plus(1, DateTimeUnit.DAY)
        }
        return rebinBoundaries(series, boundaries)
    }

    /** Monday-aligned local weeks, clipped to the selected window at both ends. */
    fun rebinLocalWeeks(series: HistorySeries, zone: TimeZone): List<HistoryColumn> {
        val boundaries = arrayListOf(series.fromS)
        val first = localDateOf(series.fromS, zone)
        // The next Monday strictly after the first local day (a Monday start moves a whole week on).
        val ahead = (DayOfWeek.MONDAY.isoDayNumber - first.dayOfWeek.isoDayNumber + 7) % 7
        var monday = first.plus(if (ahead == 0) 7 else ahead, DateTimeUnit.DAY)
        while (startOfDay(monday, zone) < series.toS) {
            val boundary = startOfDay(monday, zone)
            if (boundary > series.fromS) boundaries += boundary
            monday = monday.plus(1, DateTimeUnit.WEEK)
        }
        boundaries += series.toS
        return rebinBoundaries(series, boundaries)
    }

    /** Split UTC tier points at local boundaries so both mean and coverage use the visible span. */
    private fun rebinBoundaries(series: HistorySeries, boundaries: List<Long>): List<HistoryColumn> {
        if (boundaries.size < 2) return emptyList()
        val count = boundaries.size - 1
        val weight = DoubleArray(count)
        val sum = DoubleArray(count)
        val covered = DoubleArray(count)
        val estimated = DoubleArray(count)
        val peak = FloatArray(count) { Float.NEGATIVE_INFINITY }
        for (p in series.points) {
            if (p.endS <= series.fromS || p.startS >= series.toS) continue
            val firstBin = boundaries.binarySearch(p.startS).let { if (it >= 0) it else -it - 2 }.coerceIn(0, count - 1)
            // The peak happened at one instant, so only the bin holding most of the point gets it
            // (the earlier on a tie). The others get the mean, so max never drops below mean.
            var peakBin = firstBin
            var peakOverlap = -1L
            var i = firstBin
            while (i < count && boundaries[i] < p.endS) {
                val overlap = (minOf(p.endS, boundaries[i + 1]) - maxOf(p.startS, boundaries[i])).coerceAtLeast(0)
                if (overlap > peakOverlap) {
                    peakOverlap = overlap
                    peakBin = i
                }
                i++
            }
            i = firstBin
            while (i < count && boundaries[i] < p.endS) {
                val overlap = (minOf(p.endS, boundaries[i + 1]) - maxOf(p.startS, boundaries[i])).coerceAtLeast(0)
                if (overlap > 0) {
                    val w = overlap * p.coverage.toDouble()
                    covered[i] += w
                    if (p.estimated) estimated[i] += w
                    p.mean?.let { m ->
                        sum[i] += m * w
                        weight[i] += w
                        val hi = if (i == peakBin) p.max ?: m else m
                        if (hi > peak[i]) peak[i] = hi
                    }
                }
                i++
            }
        }
        return List(count) { i ->
            val span = (boundaries[i + 1] - boundaries[i]).toDouble()
            HistoryColumn(
                startS = boundaries[i],
                endS = boundaries[i + 1],
                mean = if (weight[i] > 0) (sum[i] / weight[i]).toFloat() else null,
                max = if (peak[i].isFinite()) peak[i] else null,
                coverage = (covered[i] / span).toFloat().coerceIn(0f, 1f),
                estimatedShare = if (covered[i] > 0) (estimated[i] / covered[i]).toFloat().coerceIn(0f, 1f) else 0f,
            )
        }
    }

    /**
     * Column width per range: 24 · 168 · 30 · 53 columns (365 days span 53 week slots). A day is drawn in **hours** (chosen
     * 2026-09-18 over 96 quarter-hours): 24 columns are tappable at phone width, the peak whisker
     * keeps a short spike visible, and the reading is the same as Week ("one column = one hour"). The
     * 15-min tier stays in Room for the Week view and for a finer zoom later.
     */
    fun slotSeconds(range: HistoryRange): Long = when (range) {
        HistoryRange.DAY -> 3_600L
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

    private fun floorTo(t: Long, step: Long, align: Long): Long = (t - align).floorDiv(step) * step + align

    /**
     * The y-axis top: the value most columns live under, not the tallest spike. Takes the 95th
     * percentile of column means (a full day of 15 µg/m³ with one 379 spike stays readable at 0–40),
     * at least [floor], rounded up to a nice step. Columns above it are clipped and get a peak cap.
     */
    fun axisMax(columns: List<HistoryColumn>, floor: Float): Float {
        val raw = axisRaw(columns, floor)
        val step = niceStep(raw)
        return ceil(raw / step) * step
    }

    /** The unrounded top of a capped axis, the 95th percentile of the means plus a fifth, never below [floor]. */
    fun axisRaw(columns: List<HistoryColumn>, floor: Float): Float {
        val means = columns.mapNotNull { it.mean }.sorted()
        val p95 = if (means.isEmpty()) 0f else means[((means.size - 1) * 0.95).toInt()]
        return max(p95 * 1.2f, floor)
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
