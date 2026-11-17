package space.opensensor.units

/**
 * The symbol a screen shows for a UCUM unit code, the `unit` of a board template column.
 *
 * - A code in the table maps to its symbol, `ug/m3` to µg/m³, `Cel` to °C, `[ppm]` to ppm.
 * - The dimensionless `1` and a code that is only an annotation, such as `{count}` or `{code}`,
 *   show no unit.
 * - An annotation inside a longer code keeps its words, `{particles}/dL` reads particles/dL.
 * - Any other code passes through unchanged, so a symbol that is already a display string
 *   (`µg/m³`, `°C`, `hPa`) stays as it is.
 *
 * Display only. [UnitConversion] keys its imperial rules on the code itself (`Cel`, `g`), never on
 * the symbol, so `[g]` (standard gravity) shows g and never converts to ounces.
 */
object UcumDisplay {
    private val symbols: Map<String, String> = mapOf(
        "ug/m3" to "µg/m³",
        "mg/m3" to "mg/m³",
        "ng/m3" to "ng/m³",
        "g/m3" to "g/m³",
        "Cel" to "°C",
        "[degF]" to "°F",
        "[ppm]" to "ppm",
        "[ppb]" to "ppb",
        "By" to "B",
        "kBy" to "kB",
        "MBy" to "MB",
        "us" to "µs",
        "uV" to "µV",
        "uA" to "µA",
        "[g]" to "g",
        "deg" to "°",
        "deg/s" to "°/s",
        "m2" to "m²",
        "m3" to "m³",
        "m/s2" to "m/s²",
        "W/m2" to "W/m²",
        "1" to "",
    )

    /** The symbol shown for [code]. See the class rules. */
    fun of(code: String): String {
        val trimmed = code.trim()
        symbols[trimmed]?.let { return it }
        if (ANNOTATION_ONLY.matches(trimmed)) return ""
        return if ('{' in trimmed) trimmed.replace(ANNOTATION) { it.groupValues[1].replace('_', ' ') } else trimmed
    }

    private val ANNOTATION_ONLY = Regex("""\{[^{}]*\}""")
    private val ANNOTATION = Regex("""\{([^{}]*)\}""")
}
