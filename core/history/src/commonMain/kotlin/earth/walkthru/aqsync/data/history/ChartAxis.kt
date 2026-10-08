package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.airquality.HealthScale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

data class ChartAxis(val min: Float, val max: Float, val step: Float) {
    val span: Float get() = max - min

    /** Fraction above the bottom of the plot; zero stays inside a signed axis. */
    fun fraction(value: Float): Float = ((value - min) / span).coerceIn(0f, 1f)
}

object ChartAxes {
    /**
     * Banded quantities start at zero and keep the 95th percentile cap (spikes above it get a
     * labelled cap), then snap up to the next WHO edge so the guideline is always in view and two
     * days on the same scale read alike. Above the loosest interim target the top is a round number.
     * Neutral quantities use the full finite mean/peak range, with room below a negative minimum;
     * ordinary signed values are never flattened onto zero or clipped to a percentile. Both signs
     * share a zero baseline.
     */
    fun of(columns: List<HistoryColumn>, metric: MetricSpec): ChartAxis {
        val finite = columns.map {
            it.copy(mean = it.mean?.takeIf(Float::isFinite), max = it.max?.takeIf(Float::isFinite))
        }
        val scale = metric.healthScale
        if (scale != null) return snapped(Columns.axisRaw(finite, metric.axisFloor), scale)
        val values = finite.flatMap { listOfNotNull(it.mean, it.max) }
        val upper = maxOf(values.maxOrNull() ?: 0f, metric.axisFloor)
        val lowest = values.minOrNull()?.coerceAtMost(0f) ?: 0f
        val lower = lowest - abs(lowest) * 0.1f
        val step = Columns.niceStep(upper - lower)
        return ChartAxis(floor(lower / step) * step, ceil(upper / step) * step, step)
    }

    /** A zero-based axis whose top is the first WHO edge at or above [raw], with a step that divides it. */
    internal fun snapped(raw: Float, scale: HealthScale): ChartAxis {
        val edge = scale.edges.firstOrNull { it >= raw }?.toFloat()
        if (edge == null) {
            val step = Columns.niceStep(raw)
            return ChartAxis(0f, ceil(raw / step) * step, step)
        }
        return ChartAxis(0f, edge, gridStep(edge))
    }

    /** 3 to 6 gridlines on whole or half units, so 37.5 reads 0, 7.5, 15 and 75 reads 0, 15, 30. */
    internal fun gridStep(max: Float): Float {
        for (k in intArrayOf(4, 5, 3, 6)) {
            val s = max / k
            if (abs(s * 2 - (s * 2).roundToInt()) < 1e-4f) return s
        }
        return Columns.niceStep(max)
    }

    /** The guideline line a chart draws for [metric], in its own unit, or null when it has none. */
    fun guidelineOf(metric: MetricSpec): Float? = metric.healthScale?.guideline?.toFloat()
}
