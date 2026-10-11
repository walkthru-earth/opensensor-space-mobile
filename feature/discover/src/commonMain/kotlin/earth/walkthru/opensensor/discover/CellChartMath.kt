package earth.walkthru.opensensor.discover

import earth.walkthru.aqsync.airquality.HealthScale
import earth.walkthru.aqsync.airquality.MoodBlock
import earth.walkthru.aqsync.airquality.WhoGuidelines
import earth.walkthru.aqsync.airquality.WhoStep
import earth.walkthru.aqsync.publicfeed.CellRow
import earth.walkthru.aqsync.publicfeed.CellScale
import earth.walkthru.aqsync.publicfeed.FeedProperty
import earth.walkthru.aqsync.publicfeed.HealthAxis
import earth.walkthru.aqsync.publicfeed.PercentileScale
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The value axis of one sheet chart. [fraction] places a value on it, 0 at [low] and 1 at [high].
 * Pure, so the host tests cover the scaling without Compose.
 */
internal data class ChartDomain(val low: Double, val high: Double) {
    fun fraction(value: Double): Float = if (high <= low) 0.5f else ((value - low) / (high - low)).coerceIn(0.0, 1.0).toFloat()

    companion object {
        /**
         * The 5th to 95th percentile of [values], the same span as the map legend, so one outlier
         * cannot squeeze the other bars. Values past it sit at the ends, as on the map. Null when
         * nothing is finite. Missing values are skipped, never read as zero.
         */
        fun of(values: List<Double>): ChartDomain? = PercentileScale.of(values)?.let { ChartDomain(it.low, it.high) }

        /**
         * The axis for [propertyId]. With a WHO scale it runs from zero to the WHO edge at or above
         * the 95th percentile ([HealthAxis.high]), so the guideline is always in view and charts
         * compare across cells. Otherwise the percentile span of [of].
         */
        fun forProperty(propertyId: String, values: List<Double>): ChartDomain? {
            val health = WhoGuidelines.forProperty(propertyId) ?: return of(values)
            if (values.none { it.isFinite() }) return null
            return ChartDomain(0.0, HealthAxis.high(health, values))
        }
    }
}

/**
 * Counts of [values] in [bins] equal slices of [domain]. Values past either end count in the end
 * bin, like a clamped map colour. Non finite values are not counted. [upperInclusive] puts a value
 * on a bin border in the lower bin, as WHO edges are read ("at or below 15").
 */
internal fun histogram(values: List<Double>, domain: ChartDomain, bins: Int, upperInclusive: Boolean = false): IntArray {
    require(bins > 0)
    val counts = IntArray(bins)
    for (v in values) {
        if (!v.isFinite()) continue
        counts[binOf(v, domain, bins, upperInclusive)]++
    }
    return counts
}

/**
 * The values one bar of [histogram] holds, the filter the map and the list apply while that bar is
 * touched. The outer bars are open ended, because they also hold the values clamped into them.
 */
internal data class ValueBand(val low: Double, val high: Double, val upperInclusive: Boolean) {
    operator fun contains(value: Double): Boolean = if (upperInclusive) value > low && value <= high else value >= low && value < high
}

internal fun bandOf(bin: Int, domain: ChartDomain, bins: Int, upperInclusive: Boolean): ValueBand {
    val step = (domain.high - domain.low) / bins
    val low = if (bin <= 0) Double.NEGATIVE_INFINITY else domain.low + step * bin
    val high = if (bin >= bins - 1) Double.POSITIVE_INFINITY else domain.low + step * (bin + 1)
    return ValueBand(low, high, upperInclusive)
}

/** The bin of [value] in [histogram]. A flat domain puts everything in the middle bin. */
internal fun binOf(value: Double, domain: ChartDomain, bins: Int, upperInclusive: Boolean = false): Int {
    if (domain.high <= domain.low) return bins / 2
    val t = (value - domain.low) / (domain.high - domain.low)
    val i = if (upperInclusive) ceil(t * bins).toInt() - 1 else (t * bins).toInt()
    return i.coerceIn(0, bins - 1)
}

/** A bin count that keeps bars readable. Few areas get few wide bars, many get up to 24. */
internal fun binCount(areas: Int): Int = (areas / 2).coerceIn(6, 24)

/**
 * Bins for a chart of [propertyId] over [domain]. With a WHO scale every edge in view falls on a
 * bin border, so each bar holds one step and can take its colour. Otherwise [binCount].
 */
internal fun chartBins(propertyId: String, domain: ChartDomain, areas: Int): Int {
    val health = WhoGuidelines.forProperty(propertyId) ?: return binCount(areas)
    return HealthAxis.bins(health, domain.high) ?: binCount(areas)
}

/** The middle value of bin [i], for a bar's step colour. */
internal fun binMid(i: Int, domain: ChartDomain, bins: Int): Double = domain.low + (domain.high - domain.low) * (i + 0.5) / bins

/** Percent of [values] strictly below [value], rounded. Null with fewer than 3 other values. */
internal fun percentBelow(values: List<Double>, value: Double): Int? {
    val finite = values.filter { it.isFinite() }
    if (finite.size < 3) return null
    return (finite.count { it < value } * 100.0 / finite.size).roundToInt()
}

/** A WHO edge as the legend prints it, "15" or "37.5". Edges are µg/m³ and never converted. */
internal fun formatEdge(value: Double): String = if (value == value.roundToInt().toDouble()) value.roundToInt().toString() else formatDecimals(value, 1)

/** One metric of the selected cell, ready to draw as a range row. */
internal data class MetricRange(
    val property: FeedProperty,
    val mean: Double,
    val min: Double?,
    val max: Double?,
    val domain: ChartDomain,
    /** The colour the map gives [mean], ARGB. A WHO step colour or the percentile ramp. */
    val color: Int,
    /** The WHO step of [mean], null for a property without a health scale. */
    val step: WhoStep? = null,
    /** The WHO guideline value, drawn as a tick on the track. */
    val guideline: Double? = null,
)

/**
 * The rows of the cell sheet, every property the cell reports with a mean, in dictionary order.
 * Each row has its own axis ([ChartDomain.forProperty]). For PM it starts at zero and ends on a WHO
 * edge, for the rest it is the 5th to 95th percentile of that property's means in view, the same
 * scale the map colours with. A min, max or mean past it is drawn at the end of the track.
 */
internal fun metricRanges(row: CellRow, rows: List<CellRow>, dictionary: List<FeedProperty>): List<MetricRange> =
    dictionary.mapNotNull { p ->
        val stat = row.stats[p.id] ?: return@mapNotNull null
        val mean = stat.mean ?: return@mapNotNull null
        val means = rows.mapNotNull { it.mean(p.id) }.ifEmpty { listOf(mean) }
        val domain = ChartDomain.forProperty(p.id, means) ?: return@mapNotNull null
        val scale = CellScale.of(p.id, means) ?: return@mapNotNull null
        val color = scale.color(mean) ?: return@mapNotNull null
        val health = scale as? CellScale.Health
        MetricRange(p, mean, stat.min, stat.max, domain, color, health?.stepOf(mean), health?.scale?.guideline)
    }

/**
 * The Simple spread. Six blocks, one per WHO step in order, each as wide as the number of areas in
 * that step. Non finite values are not counted. A block of zero areas is not drawn.
 */
internal fun stepBlocks(values: List<Double>, health: HealthScale): List<MoodBlock> {
    val finite = values.filter { it.isFinite() }
    return WhoStep.entries.map { step -> MoodBlock(step, finite.count { health.stepOf(it) == step }.toFloat()) }
}

/**
 * The values of [step], as the map and the list filter them while its block is selected. Lower
 * bound exclusive, upper inclusive, open at both ends of the scale, the same reading as [HealthScale.stepOf].
 */
internal fun stepBand(health: HealthScale, step: WhoStep): ValueBand {
    val (low, high) = health.rangeOf(step)
    return ValueBand(low ?: Double.NEGATIVE_INFINITY, high ?: Double.POSITIVE_INFINITY, upperInclusive = true)
}
