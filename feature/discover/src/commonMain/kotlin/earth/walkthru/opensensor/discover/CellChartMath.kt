package earth.walkthru.opensensor.discover

import earth.walkthru.aqsync.publicfeed.CellRow
import earth.walkthru.aqsync.publicfeed.FeedProperty
import earth.walkthru.aqsync.publicfeed.PercentileScale
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
    }
}

/**
 * Counts of [values] in [bins] equal slices of [domain]. Values past either end count in the end
 * bin, like a clamped map colour. Non finite values are not counted.
 */
internal fun histogram(values: List<Double>, domain: ChartDomain, bins: Int): IntArray {
    require(bins > 0)
    val counts = IntArray(bins)
    for (v in values) {
        if (!v.isFinite()) continue
        counts[binOf(v, domain, bins)]++
    }
    return counts
}

/** The bin of [value] in [histogram]. A flat domain puts everything in the middle bin. */
internal fun binOf(value: Double, domain: ChartDomain, bins: Int): Int {
    if (domain.high <= domain.low) return bins / 2
    val t = (value - domain.low) / (domain.high - domain.low)
    return (t * bins).toInt().coerceIn(0, bins - 1)
}

/** A bin count that keeps bars readable. Few areas get few wide bars, many get up to 24. */
internal fun binCount(areas: Int): Int = (areas / 2).coerceIn(6, 24)

/** Percent of [values] strictly below [value], rounded. Null with fewer than 3 other values. */
internal fun percentBelow(values: List<Double>, value: Double): Int? {
    val finite = values.filter { it.isFinite() }
    if (finite.size < 3) return null
    return (finite.count { it < value } * 100.0 / finite.size).roundToInt()
}

/** One metric of the selected cell, ready to draw as a range row. */
internal data class MetricRange(
    val property: FeedProperty,
    val mean: Double,
    val min: Double?,
    val max: Double?,
    val domain: ChartDomain,
    /** Where [mean] sits on the map colour scale of this property, 0..1. */
    val colorFraction: Double,
)

/**
 * The rows of the cell sheet, every property the cell reports with a mean, in dictionary order.
 * Each row has its own axis, the 5th to 95th percentile of that property's means in view, the
 * same scale the map colours with. A min, max or mean past it is drawn at the end of the track.
 */
internal fun metricRanges(row: CellRow, rows: List<CellRow>, dictionary: List<FeedProperty>): List<MetricRange> =
    dictionary.mapNotNull { p ->
        val stat = row.stats[p.id] ?: return@mapNotNull null
        val mean = stat.mean ?: return@mapNotNull null
        val means = rows.mapNotNull { it.mean(p.id) }
        val domain = ChartDomain.of(means.ifEmpty { listOf(mean) }) ?: return@mapNotNull null
        MetricRange(p, mean, stat.min, stat.max, domain, domain.fraction(mean).toDouble())
    }
