package space.opensensor.units

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UcumDisplayTest {
    @Test
    fun everyBundledTemplateUnitHasASymbol() {
        val expected = mapOf(
            "%" to "%",
            "1" to "",
            "By" to "B",
            "Cel" to "°C",
            "[ppm]" to "ppm",
            "ug/m3" to "µg/m³",
            "us" to "µs",
            "{bitmask}" to "",
            "{code}" to "",
            "{count}" to "",
            "{h3_index}" to "",
            "[g]" to "g",
            "deg/s" to "°/s",
            "mA" to "mA",
            "mV" to "mV",
            "ms" to "ms",
            "{YYYYMMDD}" to "",
            "{boolean}" to "",
            "{hhmmss}" to "",
            "{particles}/dL" to "particles/dL",
            "{pixel}" to "",
            "{raw_count}" to "",
            "%/h" to "%/h",
            "min" to "min",
        )
        expected.forEach { (code, symbol) -> assertEquals(symbol, UcumDisplay.of(code), code) }
    }

    @Test
    fun commonCodesMapToTheirSymbols() {
        assertEquals("lx", UcumDisplay.of("lx"))
        assertEquals("hPa", UcumDisplay.of("hPa"))
        assertEquals("°F", UcumDisplay.of("[degF]"))
        assertEquals("ppb", UcumDisplay.of("[ppb]"))
        assertEquals("m/s²", UcumDisplay.of("m/s2"))
        assertEquals("W/m²", UcumDisplay.of("W/m2"))
    }

    @Test
    fun anAnnotationInsideACodeReadsUnderscoresAsSpaces() {
        assertEquals("raw count/s", UcumDisplay.of("{raw_count}/s"))
    }

    @Test
    fun displayStringsAndUnknownCodesPassThroughTrimmed() {
        assertEquals("µg/m³", UcumDisplay.of("µg/m³"))
        assertEquals("°C", UcumDisplay.of("°C"))
        assertEquals("something odd", UcumDisplay.of(" something odd "))
        assertEquals("", UcumDisplay.of(""))
    }

    @Test
    fun celsiusCodeConvertsUnderImperial() {
        val (v, u) = UnitConversion.convert(0.0, "Cel", UnitSystem.IMPERIAL)
        assertTrue(abs(v - 32.0) < 1e-9)
        assertEquals("°F", u)
        assertEquals("°F", UnitConversion.displayUnit("Cel", UnitSystem.IMPERIAL))
    }

    @Test
    fun celsiusCodeShowsItsSymbolUnderMetric() {
        assertEquals(21.5 to "°C", UnitConversion.convert(21.5, "Cel", UnitSystem.METRIC))
        assertEquals("°C", UnitConversion.displayUnit("Cel", UnitSystem.METRIC))
        assertEquals("°C", UnitConversion.displayUnit("Cel", UnitSystem.AUTO))
    }

    @Test
    fun standardGravityNeverConvertsToOunces() {
        assertEquals(1.5 to "g", UnitConversion.convert(1.5, "[g]", UnitSystem.IMPERIAL))
        assertEquals("g", UnitConversion.displayUnit("[g]", UnitSystem.IMPERIAL))
    }

    @Test
    fun templateCodesWithoutARuleShowTheirSymbolUnderImperial() {
        assertEquals(12.0 to "µg/m³", UnitConversion.convert(12.0, "ug/m3", UnitSystem.IMPERIAL))
        assertEquals(400.0 to "ppm", UnitConversion.convert(400.0, "[ppm]", UnitSystem.IMPERIAL))
        assertEquals("", UnitConversion.displayUnit("{count}", UnitSystem.IMPERIAL))
    }
}
