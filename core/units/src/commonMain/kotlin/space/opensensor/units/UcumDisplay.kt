package space.opensensor.units

/**
 * The symbol a screen shows for a UCUM unit code, the `unit` of a board template column.
 *
 * - A code in the table maps to its symbol, `ug/m3` to µg/m³, `Cel` to °C, `[ppm]` to ppm.
 * - The dimensionless `1` and a code that is only an annotation, such as `{count}` or `{code}`,
 *   show no unit.
 * - An annotation inside a longer code keeps its words, `{particles}/dL` reads particles/dL.
 * - The exponent digits of a unit atom, with their sign, show as superscripts (UCUM 2.1 §8),
 *   so `m3/m3` reads m³/m³, `m2` reads m² and `s-1` reads s⁻¹. Digits after anything but a
 *   letter or `]`, such as a factor `10*3`, and digits inside an annotation stay as they are.
 * - Any other code passes through unchanged, so a symbol that is already a display string
 *   (`µg/m³`, `°C`, `hPa`) stays as it is. The symbol is the same in every language.
 *
 * Display only. [UnitConversion] keys its imperial rules on the code itself (`Cel`, `g`), never on
 * the symbol, so `[g]` (standard gravity) shows g and never converts to ounces.
 */
object UcumDisplay {
    private val symbols: Map<String, String> = mapOf(
        "ug/m3" to "µg/m³",
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
        "1" to "",
    )

    /** The symbol shown for [code]. See the class rules. */
    fun of(code: String): String {
        val trimmed = code.trim()
        symbols[trimmed]?.let { return it }
        if (ANNOTATION_ONLY.matches(trimmed)) return ""
        val out = StringBuilder()
        var at = 0
        for (match in ANNOTATION.findAll(trimmed)) {
            out.append(superscripted(trimmed.substring(at, match.range.first)))
            out.append(match.groupValues[1].replace('_', ' '))
            at = match.range.last + 1
        }
        return out.append(superscripted(trimmed.substring(at))).toString()
    }

    private fun superscripted(part: String): String = part.replace(EXPONENT) { m ->
        m.groupValues[1] + m.groupValues[2].map { SUPERSCRIPT.getValue(it) }.joinToString("")
    }

    /** A sign and digits right after a letter or `]`, the exponent of a UCUM unit atom. */
    private val EXPONENT = Regex("""([A-Za-z\]])([+-]?[0-9]+)""")
    private val SUPERSCRIPT: Map<Char, Char> = ("0123456789-+".toList()).zip("⁰¹²³⁴⁵⁶⁷⁸⁹⁻⁺".toList()).toMap()

    private val ANNOTATION_ONLY = Regex("""\{[^{}]*\}""")
    private val ANNOTATION = Regex("""\{([^{}]*)\}""")
}
