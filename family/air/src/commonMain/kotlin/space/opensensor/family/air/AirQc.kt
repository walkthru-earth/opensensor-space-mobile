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

    override fun flags(timeMs: LongArray, values: List<FloatArray>): IntArray {
        val n = timeMs.size
        fun column(k: Int) = values[k].let { v -> DoubleArray(n) { v[it].toDouble() } }
        val counts = List(bins.size) { column(FIRST_BIN + it) }
        val width = IntArray(n) { i -> if (bins.isNotEmpty() && counts.all { it[i].isFinite() }) bins.size else -1 }
        return QcRules.flags(QcRules.Columns(timeMs, column(0), column(1), column(2), column(3), counts, width))
    }

    companion object {
        /** The id names the rules and their [QcRules.VERSION]. */
        const val ID_PREFIX = "air-qc-v"

        private const val FIRST_BIN = 4
    }
}
