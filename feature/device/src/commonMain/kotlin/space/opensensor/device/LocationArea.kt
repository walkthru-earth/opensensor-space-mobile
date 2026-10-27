package space.opensensor.device

import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * The area a sensor location covers at each precision, in words people know. The form names the
 * choice by its size, such as "About 0.74 km²", and only Detailed adds the H3 resolution.
 */
object LocationArea {
    /** The unit a [label] is written in. */
    enum class Unit { KM2, M2 }

    /** A rounded area to show, [value] already formatted with two significant digits. */
    data class Label(val value: String, val unit: Unit)

    /** Average H3 cell area in km² for resolutions 0 to 15, from the H3 cell statistics table. */
    private val AVERAGE_KM2 = doubleArrayOf(
        4_357_449.416, 609_788.442, 86_801.780, 12_393.435, 1_770.348, 252.904, 36.129, 5.161,
        0.7373276, 0.1053325, 0.0150475, 0.0021496, 0.0003071, 0.0000439, 0.0000063, 0.0000009,
    )

    /** Areas from this size down read in m². */
    private const val M2_BELOW_KM2 = 0.1

    /** The average area of one cell at [resolution], 0 to 15. */
    fun km2(resolution: Int): Double {
        require(resolution in AVERAGE_KM2.indices) { "resolution must be 0 to 15" }
        return AVERAGE_KM2[resolution]
    }

    fun label(resolution: Int): Label {
        val km2 = km2(resolution)
        return if (km2 < M2_BELOW_KM2) Label(twoDigits(km2 * 1_000_000), Unit.M2) else Label(twoDigits(km2), Unit.KM2)
    }

    /** Rounds [x] above zero to two significant digits, digits grouped by three with commas. */
    internal fun twoDigits(x: Double): String {
        require(x > 0) { "area must be positive" }
        val exponent = floor(log10(x)).toInt()
        if (exponent >= 1) {
            val step = 10.0.pow(exponent - 1)
            return grouped((x / step).roundToLong() * step.roundToLong())
        }
        val decimals = 1 - exponent
        val scale = 10.0.pow(decimals).roundToLong()
        val scaled = (x * scale).roundToLong()
        val whole = scaled / scale
        val fraction = (scaled % scale).toString().padStart(decimals, '0').trimEnd('0')
        return if (fraction.isEmpty()) grouped(whole) else "${grouped(whole)}.$fraction"
    }

    private fun grouped(n: Long): String = n.toString().reversed().chunked(3).joinToString(",").reversed()
}
