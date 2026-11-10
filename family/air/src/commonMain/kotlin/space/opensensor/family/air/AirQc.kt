package space.opensensor.family.air

import space.opensensor.model.RowCheck

/**
 * The [QcRules] of issue #91 as the [RowCheck] the daily builder runs on a thing-day of stored rows
 * ([AirFamily.rowCheck]). It reads the atmospheric PM1, PM2.5 and PM10, the relative humidity and
 * the particle count [bins] the template declares. A row missing one of those bins has no counts,
 * as in [LiveQc], so the count checks skip it.
 */
data class AirQc(val bins: List<String>) : RowCheck {
    override val id: String get() = "$ID_PREFIX${QcRules.VERSION}"

    override val columns: List<String> = listOf(
        AirFamily.PM1_ATMOSPHERIC,
        AirFamily.PM25_ATMOSPHERIC,
        AirFamily.PM10_ATMOSPHERIC,
        AirFamily.RELATIVE_HUMIDITY,
    ) + bins

    override fun flags(timeMs: LongArray, values: List<DoubleArray>): IntArray {
        val n = timeMs.size
        val counts = List(bins.size) { values[FIRST_BIN + it] }
        val width = IntArray(n) { i -> if (bins.isNotEmpty() && counts.all { it[i].isFinite() }) bins.size else -1 }
        return QcRules.flags(QcRules.Columns(timeMs, values[0], values[1], values[2], values[3], counts, width))
    }

    companion object {
        /** The id names the rules and their [QcRules.VERSION]. */
        const val ID_PREFIX = "air-qc-v"

        private const val FIRST_BIN = 4
    }
}
