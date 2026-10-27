package space.opensensor.units

import kotlin.math.abs
import kotlin.math.round
import kotlin.math.roundToLong

/**
 * The measurement system the user picked in Settings. [AUTO] follows the phone (its region, or the
 * system measurement setting where the platform exposes one). Display only: stored readings,
 * Parquet files, exports, scales, bands and colours always stay on the original SI values.
 */
enum class UnitSystem {
    AUTO,
    METRIC,
    IMPERIAL,
    ;

    /** [METRIC] or [IMPERIAL]. [AUTO] resolves from [regionCode] (ISO 3166 alpha-2, any case). */
    fun resolve(regionCode: String?): UnitSystem = if (this == AUTO) forRegion(regionCode) else this

    companion object {
        /**
         * Regions whose everyday units are US customary (CLDR `measurementSystem` US), plus the US
         * territories that inherit them. The United Kingdom uses Celsius and hectopascals for weather,
         * so it stays metric here.
         */
        val IMPERIAL_REGIONS: Set<String> = setOf("US", "LR", "MM", "PR", "GU", "VI", "AS", "MP", "UM")

        /** The system a region uses. Unknown or missing regions are metric. */
        fun forRegion(regionCode: String?): UnitSystem =
            if (regionCode?.trim()?.uppercase() in IMPERIAL_REGIONS) IMPERIAL else METRIC

        /** The stored name back to a value. Anything unknown is [AUTO]. */
        fun parse(name: String?): UnitSystem = entries.firstOrNull { it.name == name } ?: AUTO
    }
}

/** The phone's own measurement system, [UnitSystem.METRIC] or [UnitSystem.IMPERIAL]. */
expect fun systemUnitSystem(): UnitSystem

/**
 * The one conversion table. Keys are the SI unit strings that appear in the metric registry and the
 * public feed dictionary (plus a few spellings of each). Units missing from the table (µg/m³, ppm,
 * ppb, %, dB, lux, mV, AQI, counts, indices) and every unknown string pass through untouched.
 */
object UnitConversion {
    private class Rule(val to: String, val decimals: Int, val forward: (Double) -> Double, val inverse: (Double) -> Double)

    private fun scale(to: String, factor: Double, decimals: Int) = Rule(to, decimals, { it * factor }, { it / factor })

    private val celsius = Rule("°F", 1, { it * 9.0 / 5.0 + 32.0 }, { (it - 32.0) * 5.0 / 9.0 })
    private val kelvin = Rule("°F", 1, { (it - 273.15) * 9.0 / 5.0 + 32.0 }, { (it - 32.0) * 5.0 / 9.0 + 273.15 })

    private const val IN_HG_PER_HPA = 1.0 / 33.86389
    private const val FT_PER_M = 1.0 / 0.3048
    private const val MI_PER_KM = 1.0 / 1.609344
    private const val MPH_PER_MS = 3600.0 / 1609.344
    private const val IN_PER_MM = 1.0 / 25.4

    private val rules: Map<String, Rule> = buildMap {
        listOf("°C", "℃", "degC").forEach { put(it, celsius) }
        put("K", kelvin)
        listOf("hPa", "mbar", "mb").forEach { put(it, scale("inHg", IN_HG_PER_HPA, 2)) }
        put("Pa", scale("inHg", IN_HG_PER_HPA / 100.0, 2))
        put("kPa", scale("inHg", IN_HG_PER_HPA * 10.0, 2))
        put("mm", scale("in", IN_PER_MM, 2))
        put("cm", scale("in", IN_PER_MM * 10.0, 1))
        put("m", scale("ft", FT_PER_M, 0))
        put("km", scale("mi", MI_PER_KM, 1))
        put("m/s", scale("mph", MPH_PER_MS, 1))
        listOf("km/h", "kmh", "kph").forEach { put(it, scale("mph", MI_PER_KM, 1)) }
        listOf("mm/h", "mm/hr").forEach { put(it, scale("in/h", IN_PER_MM, 2)) }
        put("L", scale("gal", 1.0 / 3.785411784, 2))
        put("mL", scale("fl oz", 1.0 / 29.5735295625, 1))
        put("m³", scale("ft³", 35.31466672148859, 1))
        put("kg", scale("lb", 1.0 / 0.45359237, 1))
        put("g", scale("oz", 1.0 / 28.349523125, 1))
    }

    private fun rule(unit: String, system: UnitSystem): Rule? = if (system == UnitSystem.IMPERIAL) rules[unit.trim()] else null

    /** True when [unit] changes under [system]. */
    fun converts(unit: String, system: UnitSystem): Boolean = rule(unit, system) != null

    /**
     * [value] in [unit] as shown under [system]: the converted value and its unit string. [system]
     * should be resolved; [UnitSystem.AUTO] and [UnitSystem.METRIC] return the input unchanged.
     */
    fun convert(value: Double, unit: String, system: UnitSystem): Pair<Double, String> {
        val r = rule(unit, system) ?: return value to unit
        return r.forward(value) to r.to
    }

    /** The unit string shown under [system]. */
    fun displayUnit(unit: String, system: UnitSystem): String = rule(unit, system)?.to ?: unit

    /** Decimals to show for a converted value, or null when [unit] does not convert (keep the caller's). */
    fun displayDecimals(unit: String, system: UnitSystem): Int? = rule(unit, system)?.decimals

    /** A shown value back to [unit] (the SI side). The inverse of [convert]. */
    fun toSource(shown: Double, unit: String, system: UnitSystem): Double = rule(unit, system)?.inverse?.invoke(shown) ?: shown
}

/**
 * A resolved [UnitSystem] for screens. Construct it once per composition with the resolved system;
 * [UnitSystem.AUTO] is treated as metric. Missing values stay null.
 */
class UnitFormatter(system: UnitSystem) {
    val system: UnitSystem = if (system == UnitSystem.AUTO) UnitSystem.METRIC else system

    fun value(value: Double, unit: String): Double = UnitConversion.convert(value, unit, system).first

    fun value(value: Double?, unit: String): Double? = value?.let { value(it, unit) }

    fun value(value: Float, unit: String): Float = value(value.toDouble(), unit).toFloat()

    fun value(value: Float?, unit: String): Float? = value?.let { value(it, unit) }

    fun unit(unit: String): String = UnitConversion.displayUnit(unit, system)

    /** Decimals for a converted value, or null when the unit is shown as is. */
    fun decimals(unit: String): Int? = UnitConversion.displayDecimals(unit, system)

    fun converts(unit: String): Boolean = UnitConversion.converts(unit, system)

    /**
     * [value] in [unit] as this system shows it, rounded to the profile's display [step], which
     * prints with [stepDecimals] decimals. A unit this system converts keeps its own decimals.
     */
    fun format(value: Double, unit: String, step: Double, stepDecimals: Int): String {
        val shown = value(value, unit)
        val decimals = decimals(unit)
        return if (decimals != null) formatFixed(shown, decimals) else formatFixed(round(shown / step) * step, stepDecimals)
    }

    companion object {
        val Metric = UnitFormatter(UnitSystem.METRIC)
    }
}

/** [value] with exactly [decimals] decimals, `.` as separator. Common code has no `String.format`. */
fun formatFixed(value: Double, decimals: Int): String {
    if (!value.isFinite()) return value.toString()
    var scale = 1L
    repeat(decimals) { scale *= 10 }
    val scaled = (abs(value) * scale).roundToLong()
    val whole = scaled / scale
    val frac = scaled % scale
    val sign = if (value < 0 && scaled != 0L) "-" else ""
    return if (decimals == 0) "$sign$whole" else "$sign$whole." + frac.toString().padStart(decimals, '0')
}
