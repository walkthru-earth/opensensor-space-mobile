package space.opensensor.publicfeed

import space.opensensor.model.Scale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * How the map, the legend and the sheet colour one property (docs/devices-and-discovery.md,
 * Part 1 rule 3). A property whose feed gives it a scale (`opensensor:scales`, published by the
 * family the property belongs to) is coloured by fixed steps, so a colour means the same in every place, viewport
 * and window. Every other property uses the neutral viridis ramp stretched over the cells in view.
 * Colours are ARGB.
 */
sealed interface CellScale {
    /** The colour of [value], or null when it has none (a non-finite value). */
    fun color(value: Double): Int?

    /** Fixed steps. The colour of a value never depends on the other cells. */
    data class Steps(val scale: StepScale) : CellScale {
        override fun color(value: Double): Int? = scale.colorOf(value)

        fun stepOf(value: Double): Int? = scale.stepOf(value)
    }

    /** The 5th to 95th percentile of the cells in view on viridis, for a property without a scale. */
    data class Relative(val percentile: PercentileScale) : CellScale {
        override fun color(value: Double): Int? = if (value.isFinite()) percentile.color(value) else null
    }

    companion object {
        /**
         * The scale for [property]. Its usable published scale wins and needs no values. Otherwise
         * the percentile ramp of [values], null when none is finite.
         */
        fun of(property: FeedProperty, values: List<Double>): CellScale? =
            property.scale?.let { StepScale.of(property.id, it) }?.let { Steps(it) }
                ?: PercentileScale.of(values)?.let { Relative(it) }
    }
}

/**
 * A published [Scale] ready to colour and label one property. Build it with [of], which refuses a
 * scale the app cannot read in order.
 */
class StepScale private constructor(val propertyId: String, val scale: Scale) {
    val size: Int get() = scale.steps.size

    /** The finite upper bounds, ascending. One fewer than [size] when the last step is open. */
    val edges: List<Double> = scale.steps.mapNotNull { it.upper }

    /** The reference line charts draw, null when the scale names none. */
    val guideline: Double? get() = scale.guideline

    /** ARGB per step. The step's `color_hint`, or the viridis ramp at its place when it has none. */
    val colors: List<Int> = scale.steps.mapIndexed { i, step ->
        step.colorHint?.let(::argbOf) ?: SequentialRamp.at(if (size == 1) 0.5 else i.toDouble() / (size - 1))
    }

    /**
     * The step [value] falls in, upper bound inclusive. A finite value above a closed last step is
     * drawn in the last step, the way the map clamps. Null for a non-finite value.
     */
    fun stepOf(value: Double): Int? {
        if (!value.isFinite()) return null
        return scale.stepIndexOf(value).takeIf { it >= 0 } ?: (size - 1)
    }

    fun colorOf(value: Double): Int? = stepOf(value)?.let { colors[it] }

    /** The value range of step [i], lower bound exclusive (null for the first), upper inclusive (null when open). */
    fun rangeOf(i: Int): Pair<Double?, Double?> = scale.steps.getOrNull(i - 1)?.upper to scale.steps[i].upper

    /** The name of step [i] in [language], else English, else the first text. */
    fun label(i: Int, language: String): String = scale.steps[i].label.get(language)

    /** True when text on step [i]'s colour should be light (relative luminance under 0.179). */
    fun darkFill(i: Int): Boolean = luminance(colors[i]) < DARK_LUMINANCE

    override fun equals(other: Any?): Boolean = other is StepScale && other.propertyId == propertyId && other.scale == scale

    override fun hashCode(): Int = 31 * propertyId.hashCode() + scale.hashCode()

    override fun toString(): String = "StepScale($propertyId, ${scale.id})"

    companion object {
        private const val DARK_LUMINANCE = 0.179

        /**
         * The most steps a scale may have. The bundled ones have 6, and the legend, the step bar and the chart bins
         * walk every step, so a feed cannot make them draw thousands.
         */
        const val MAX_STEPS: Int = 12

        /**
         * The largest magnitude an edge or guideline may have. Sensor readings in the feed stay far
         * below it (CO2 in ppm and pressure in Pa near 1e5, particle counts near 1e7), and it keeps
         * every edge exact as a Long count of half units in [StepAxis.bins].
         */
        const val MAX_MAGNITUDE: Double = 1e9

        /**
         * [scale] for [propertyId], or null when the app cannot read it. It needs at most [MAX_STEPS]
         * steps, finite upper bounds ascending strictly with only the last step open, and every edge
         * and the guideline finite and within [MAX_MAGNITUDE].
         */
        fun of(propertyId: String, scale: Scale): StepScale? {
            if (scale.steps.size > MAX_STEPS) return null
            if (scale.guideline?.let { !it.isFinite() || abs(it) > MAX_MAGNITUDE } == true) return null
            val uppers = scale.steps.map { it.upper }
            if (uppers.dropLast(1).any { it == null || !it.isFinite() }) return null
            val edges = uppers.filterNotNull()
            if (edges.any { !it.isFinite() || abs(it) > MAX_MAGNITUDE } || edges.zipWithNext().any { (a, b) -> a >= b }) return null
            return StepScale(propertyId, scale)
        }

        private fun argbOf(hex: String): Int? =
            hex.takeIf { it.length == 6 }?.toLongOrNull(16)?.let { (0xFF000000L or it).toInt() }

        private fun luminance(argb: Int): Double {
            fun channel(shift: Int): Double {
                val c = ((argb shr shift) and 0xFF) / 255.0
                return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
            }
            return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
        }
    }
}

/**
 * The value axis of a sheet chart for a property with a [StepScale]. It starts at zero and ends on
 * a step edge, so the guideline is always in view and charts read alike from cell to cell.
 */
object StepAxis {
    /** Bars a chart may draw before they get too thin to read. */
    const val MAX_BINS = 30
    private const val MIN_BINS = 6

    /**
     * The top of the axis. The 95th percentile of [values] (the same clipping as the percentile
     * ramp, so one outlier cannot squeeze the rest) snapped up to the smallest edge at or above it,
     * at least the guideline. Past the last edge the percentile itself is the top. Values above the
     * top are drawn capped and marked. The guideline, else the first edge, when nothing is finite.
     */
    fun high(scale: StepScale, values: List<Double>): Double {
        val floor = scale.guideline ?: scale.edges.firstOrNull() ?: 0.0
        val visible = PercentileScale.of(values)?.high ?: return floor
        val wanted = maxOf(visible, floor)
        return scale.edges.firstOrNull { it >= wanted } ?: wanted
    }

    /**
     * A bin count for 0..[high] whose bin borders fall on every step edge inside it, so each bar
     * holds one step and takes its colour. Between 6 and [MAX_BINS] bins, as many as fit up to 24.
     * Null when no such count exists (a top past the last edge), and the chart falls back to its
     * usual bins.
     */
    fun bins(scale: StepScale, high: Double): Int? {
        if (!high.isFinite() || high <= 0.0) return null
        // Bin widths are searched in halves of the scale unit, the grid the bundled edges sit on. A
        // scale whose edges do not has no such count.
        val unit = 0.5
        val inside = scale.edges.filter { it <= high + EPS } + high
        val units = inside.map { it / unit }
        if (units.any { abs(it - it.roundToLong()) > EPS }) return null
        val top = units.last().roundToLong()
        // A top below half a unit rounds to zero and has no bins, and it would divide by zero below.
        if (top <= 0L) return null
        // Bounded by the bin counts and the steps, never by the size of [high].
        val fits = (MIN_BINS..MAX_BINS).filter { n -> top % n == 0L && units.all { it.roundToLong() % (top / n) == 0L } }
        return fits.filter { it <= 24 }.maxOrNull() ?: fits.minOrNull()
    }

    private const val EPS = 1e-9
}
