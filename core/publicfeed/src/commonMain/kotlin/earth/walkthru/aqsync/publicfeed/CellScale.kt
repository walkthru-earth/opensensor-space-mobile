package earth.walkthru.aqsync.publicfeed

import earth.walkthru.aqsync.airquality.HealthScale
import earth.walkthru.aqsync.airquality.WhoGuidelines
import earth.walkthru.aqsync.airquality.WhoStep
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * How the map, the legend and the sheet colour one property (docs/devices-and-discovery.md,
 * Part 1 rule 3). A property with a published health scale (PM2.5 and PM10, WHO 2021) is coloured
 * by fixed steps, so a colour means the same in every place, viewport and window. Every other
 * property uses the neutral viridis ramp stretched over the cells in view. Colours are ARGB.
 */
sealed interface CellScale {
    /** The colour of [value], or null when it has none (a non-finite value). */
    fun color(value: Double): Int?

    /** Fixed WHO steps. The colour of a value never depends on the other cells. */
    data class Health(val scale: HealthScale) : CellScale {
        override fun color(value: Double): Int? = scale.colorOf(value)

        fun stepOf(value: Double): WhoStep? = scale.stepOf(value)
    }

    /** The 5th to 95th percentile of the cells in view on viridis, for a property without a scale. */
    data class Relative(val percentile: PercentileScale) : CellScale {
        override fun color(value: Double): Int? = if (value.isFinite()) percentile.color(value) else null
    }

    companion object {
        /**
         * The scale for [propertyId]. A published health scale wins and needs no values. Otherwise
         * the percentile ramp of [values], null when none is finite.
         */
        fun of(propertyId: String, values: List<Double>): CellScale? =
            WhoGuidelines.forProperty(propertyId)?.let { Health(it) } ?: PercentileScale.of(values)?.let { Relative(it) }
    }
}

/**
 * The value axis of a sheet chart for a property with a health scale. It starts at zero and ends
 * on a WHO edge, so the guideline is always in view and charts read alike from cell to cell.
 */
object HealthAxis {
    /** Bars a chart may draw before they get too thin to read. */
    const val MAX_BINS = 30
    private const val MIN_BINS = 6

    /**
     * The top of the axis. The 95th percentile of [values] (the same clipping as the percentile
     * ramp, so one outlier cannot squeeze the rest) snapped up to the smallest edge at or above it,
     * at least the guideline. Past the last edge the percentile itself is the top. Values above the
     * top are drawn capped and marked. The guideline when nothing is finite.
     */
    fun high(scale: HealthScale, values: List<Double>): Double {
        val visible = PercentileScale.of(values)?.high ?: return scale.guideline
        return scale.edges.firstOrNull { it >= visible } ?: visible
    }

    /**
     * A bin count for 0..[high] whose bin borders fall on every WHO edge inside it, so each bar
     * holds one step and takes its colour. Between 6 and [MAX_BINS] bins, as many as fit up to 24.
     * Null when no such count exists (a top past the last edge), and the chart falls back to its
     * usual bins.
     */
    fun bins(scale: HealthScale, high: Double): Int? {
        if (!high.isFinite() || high <= 0.0) return null
        // Edges are multiples of 0.5 µg/m³, so bin widths are searched in halves.
        val unit = 0.5
        val inside = scale.edges.filter { it <= high + EPS } + high
        val units = inside.map { it / unit }
        if (units.any { abs(it - it.roundToLong()) > EPS }) return null
        val top = units.last().roundToLong()
        val fits = (1..top).filter { w -> units.all { it.roundToLong() % w == 0L } }
            .map { w -> (top / w).toInt() }
            .filter { it in MIN_BINS..MAX_BINS }
        return fits.filter { it <= 24 }.maxOrNull() ?: fits.minOrNull()
    }

    private const val EPS = 1e-9
}
