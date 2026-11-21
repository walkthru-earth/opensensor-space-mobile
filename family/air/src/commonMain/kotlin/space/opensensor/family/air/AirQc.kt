package space.opensensor.family.air

import space.opensensor.model.RowCheck

/**
 * The [QcRules] of issue #91 as the [RowCheck] the daily builder runs on a thing-day of stored rows
 * ([AirFamily.rowCheck]). It reads the template's PM1, PM2.5, PM10 and relative humidity columns and
 * the particle count [bins] it declares, each found by property ([AirColumns]). A column the template
 * lacks reads as missing, so the checks that need it skip. A row missing one of the bins has no
 * counts, as in [LiveQc], so the count checks skip it.
 */
data class AirQc(val pm1: String?, val pm25: String, val pm10: String?, val rh: String?, val bins: List<String>) : RowCheck {
    override val id: String get() = "$ID_PREFIX${QcRules.VERSION}"

    override val columns: List<String> = listOf(pm1.orEmpty(), pm25, pm10.orEmpty(), rh.orEmpty()) + bins

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
