package space.opensensor.publicfeed

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * A neutral sequential colour scale stretched from the 5th to the 95th percentile of the cells in
 * view, as the website does (`computeRange` in `components/globe/data/constants.ts`). Used for
 * every property without a published scale. Colours are ARGB.
 */
data class PercentileScale(val low: Double, val high: Double) {

    /** Position of [value] on the scale, 0 at [low] and 1 at [high], clamped. */
    fun fraction(value: Double): Double = if (high <= low) 0.5 else ((value - low) / (high - low)).coerceIn(0.0, 1.0)

    fun color(value: Double): Int = SequentialRamp.at(fraction(value))

    companion object {
        /** Null when no value is finite. Same ranks as the website (floor of n × 0.05 and n × 0.95). */
        fun of(values: List<Double>): PercentileScale? {
            val finite = values.filter { it.isFinite() }.sorted()
            if (finite.isEmpty()) return null
            val lo = floor(finite.size * 0.05).toInt()
            val hi = floor(finite.size * 0.95).toInt().coerceAtMost(finite.lastIndex)
            return PercentileScale(finite[lo], finite[hi])
        }
    }
}

/**
 * Viridis, a perceptually uniform, colour-blind safe ramp. Scientific, so it never borrows brand
 * colours (Part 1, rule 3).
 */
object SequentialRamp {
    val stops: IntArray = intArrayOf(
        0xFF440154.toInt(), 0xFF482878.toInt(), 0xFF3E4A89.toInt(), 0xFF31688E.toInt(), 0xFF26828E.toInt(),
        0xFF1F9E89.toInt(), 0xFF35B779.toInt(), 0xFF6DCD59.toInt(), 0xFFB4DE2C.toInt(), 0xFFFDE725.toInt(),
    )

    fun at(fraction: Double): Int {
        val t = fraction.coerceIn(0.0, 1.0) * (stops.size - 1)
        val i = floor(t).toInt().coerceAtMost(stops.size - 2)
        val f = t - i
        val a = stops[i]
        val b = stops[i + 1]
        fun channel(shift: Int): Int {
            val ca = (a ushr shift) and 0xFF
            val cb = (b ushr shift) and 0xFF
            return (ca + (cb - ca) * f).roundToInt() and 0xFF
        }
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    fun hex(argb: Int): String = "#" + (argb and 0xFFFFFF).toString(16).padStart(6, '0')
}

/** Ranks and picks the properties the metric chips show. */
object PropertyRanking {
    /**
     * Properties reported by at least one of [rows], most reported first, ties in dictionary order.
     * Only properties in [dictionary] are ranked, so a chip always has a label and a unit.
     */
    fun rank(rows: List<CellRow>, dictionary: List<FeedProperty>): List<FeedProperty> {
        val counts = dictionary.associateWith { p -> rows.count { it.mean(p.id) != null } }
        return dictionary.filter { (counts[it] ?: 0) > 0 }.sortedByDescending { counts[it] ?: 0 }
    }

    /**
     * The chip row. At most [limit] chips, ranked by visible cells. The [selected] property always
     * keeps a chip, replacing the last ranked one when it would not make the row.
     */
    fun chips(ranked: List<FeedProperty>, selected: String?, limit: Int = 3): List<FeedProperty> {
        val top = ranked.take(limit)
        val chosen = ranked.firstOrNull { it.id == selected } ?: return top
        return if (chosen in top) top else top.take(limit - 1) + chosen
    }
}

/** Freshness of a cell, from the doc's cell styles. */
object CellFreshness {
    /** A cell with no hourly value for 3 hours is stale. */
    const val STALE_AFTER_MS: Long = 3 * 60 * 60 * 1000L

    /** [property] is the one on screen, so another property's newer value never hides a stale one. */
    fun isStale(row: CellRow, nowEpochMs: Long, property: String? = null): Boolean = nowEpochMs - row.updatedEpochMs(property) >= STALE_AFTER_MS

    /**
     * The newest day a daily row measured [property], or any property when null. Its
     * `updatedEpochMs` is the UTC midnight after that day.
     */
    fun dataDay(row: CellRow, property: String? = null): LocalDate = dataDay(row.updatedEpochMs(property))

    /** [dataDay] for a row's `updatedEpochMs` alone. */
    fun dataDay(updatedEpochMs: Long): LocalDate = LocalDate.fromEpochDays(updatedEpochMs.floorDiv(DAY_MS) - 1)

    /**
     * A daily row is stale when its newest day is older than the day before [asOf], the partition
     * date. A daily feed lags by days on purpose, so the clock never decides. [property] is the one on
     * screen, judged by its own newest day.
     */
    fun isStaleDaily(row: CellRow, asOf: LocalDate, property: String? = null): Boolean = dataDay(row, property) < asOf.minus(1, DateTimeUnit.DAY)

    private const val DAY_MS = 86_400_000L
}

/**
 * Builds the one GeoJSON source of the map. Each cell is a polygon with properties `h3` (hex),
 * `color` (fill from [CellScale], a fixed WHO step colour or the percentile ramp, absent when the
 * cell does not measure the property), `measured` and `stale`.
 * Built off the main thread after the camera rests.
 */
object CellGeoJson {
    fun build(
        h3: H3Grid,
        rows: List<CellRow>,
        property: String?,
        scale: CellScale?,
        staleAt: (CellRow) -> Boolean,
    ): String {
        val sb = StringBuilder(rows.size * 400 + 64)
        sb.append("{\"type\":\"FeatureCollection\",\"features\":[")
        var first = true
        for (row in rows) {
            val ring = unwrap(h3.cellToBoundary(row.h3))
            if (ring.isEmpty()) continue
            if (!first) sb.append(',')
            first = false
            val value = property?.let { row.mean(it) }
            sb.append("{\"type\":\"Feature\",\"properties\":{\"h3\":\"").append(H3Index.toHex(row.h3)).append('"')
            sb.append(",\"measured\":").append(value != null)
            // The mean itself, so a chart can filter the map without rebuilding this source.
            if (value != null && value.isFinite()) sb.append(",\"v\":").append(value)
            sb.append(",\"stale\":").append(staleAt(row))
            val color = value?.let { scale?.color(it) }
            if (color != null) sb.append(",\"color\":\"").append(SequentialRamp.hex(color)).append('"')
            sb.append("},\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[")
            for ((i, p) in (ring + ring.first()).withIndex()) {
                if (i > 0) sb.append(',')
                sb.append('[').append(coord(p.lng)).append(',').append(coord(p.lat)).append(']')
            }
            sb.append("]]}}")
        }
        sb.append("]}")
        return sb.toString()
    }

    /** Keeps a ring that crosses the antimeridian continuous, so it is not drawn across the world. */
    internal fun unwrap(ring: List<LatLng>): List<LatLng> {
        if (ring.isEmpty()) return ring
        val minLng = ring.minOf { it.lng }
        val maxLng = ring.maxOf { it.lng }
        if (maxLng - minLng <= 180.0) return ring
        return ring.map { if (it.lng < 0) it.copy(lng = it.lng + 360.0) else it }
    }

    private fun coord(v: Double): String = ((v * 1e6).roundToInt() / 1e6).toString()
}
