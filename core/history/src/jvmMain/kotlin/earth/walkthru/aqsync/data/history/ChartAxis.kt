package earth.walkthru.aqsync.data.history

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

data class ChartAxis(val min: Float, val max: Float, val step: Float) {
    val span: Float get() = max - min

    /** Fraction above the bottom of the plot; zero stays inside a signed axis. */
    fun fraction(value: Float): Float = ((value - min) / span).coerceIn(0f, 1f)
}

object ChartAxes {
    /**
     * Banded quantities retain the existing labelled positive-peak caps. Neutral quantities use
     * the full finite mean/peak range, with room below a negative minimum; ordinary signed values
     * are never flattened onto zero or clipped to a percentile. Both signs share a zero baseline.
     */
    fun of(columns: List<HistoryColumn>, metric: MetricSpec): ChartAxis {
        val finite = columns.map {
            it.copy(mean = it.mean?.takeIf(Float::isFinite), max = it.max?.takeIf(Float::isFinite))
        }
        val values = finite.flatMap { listOfNotNull(it.mean, it.max) }
        val upper = if (metric.banded) Columns.axisMax(finite, metric.axisFloor) else maxOf(values.maxOrNull() ?: 0f, metric.axisFloor)
        val lowest = if (metric.banded) 0f else values.minOrNull()?.coerceAtMost(0f) ?: 0f
        val lower = lowest - abs(lowest) * 0.1f
        val step = Columns.niceStep(upper - lower)
        return ChartAxis(floor(lower / step) * step, ceil(upper / step) * step, step)
    }

    /** A PM2.5 health guideline has no meaning for temperature, humidity or another banded metric. */
    fun hasPm25Guideline(metric: MetricSpec): Boolean = metric.id == Metrics.PM25.id && metric.column == Metrics.PM25.column && metric.unit == Metrics.PM25.unit
}
