package earth.walkthru.aqsync.units

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UnitConversionTest {
    private val imperial = UnitSystem.IMPERIAL

    private fun close(expected: Double, actual: Double, eps: Double = 1e-6) =
        assertTrue(kotlin.math.abs(expected - actual) < eps, "expected $expected but was $actual")

    @Test
    fun celsiusToFahrenheitIsAffine() {
        assertEquals(32.0 to "°F", UnitConversion.convert(0.0, "°C", imperial))
        assertEquals(212.0 to "°F", UnitConversion.convert(100.0, "°C", imperial))
        close(-40.0, UnitConversion.convert(-40.0, "°C", imperial).first)
        close(70.7, UnitConversion.convert(21.5, "°C", imperial).first)
        close(32.0, UnitConversion.convert(273.15, "K", imperial).first)
    }

    @Test
    fun scaledUnits() {
        close(29.92, UnitConversion.convert(1013.25, "hPa", imperial).first, 2e-3)
        close(1.0, UnitConversion.convert(25.4, "mm", imperial).first)
        close(1.0, UnitConversion.convert(1.609344, "km/h", imperial).first)
        close(2.2369363, UnitConversion.convert(1.0, "m/s", imperial).first)
        close(3.2808399, UnitConversion.convert(1.0, "m", imperial).first)
        close(1.0, UnitConversion.convert(1.609344, "km", imperial).first)
        assertEquals("inHg", UnitConversion.displayUnit("hPa", imperial))
        assertEquals("mph", UnitConversion.displayUnit("m/s", imperial))
        assertEquals("ft", UnitConversion.displayUnit("m", imperial))
        assertEquals("mi", UnitConversion.displayUnit("km", imperial))
        assertEquals("in", UnitConversion.displayUnit("mm", imperial))
    }

    @Test
    fun roundTripsBackToSource() {
        listOf("°C", "K", "hPa", "Pa", "kPa", "mm", "cm", "m", "km", "m/s", "km/h", "mm/h", "L", "mL", "m³", "kg", "g").forEach { unit ->
            listOf(-12.5, 0.0, 3.25, 1013.0).forEach { v ->
                val shown = UnitConversion.convert(v, unit, imperial).first
                close(v, UnitConversion.toSource(shown, unit, imperial), 1e-9)
            }
        }
    }

    @Test
    fun unknownAndNonConvertibleUnitsPassThrough() {
        listOf("µg/m³", "ppm", "ppb", "%", "dB", "lux", "mV", "AQI", "per 0.1 L", "something odd", "").forEach { unit ->
            assertEquals(12.3 to unit, UnitConversion.convert(12.3, unit, imperial))
            assertFalse(UnitConversion.converts(unit, imperial))
            assertNull(UnitConversion.displayDecimals(unit, imperial))
        }
    }

    @Test
    fun metricAndAutoAreIdentity() {
        assertEquals(21.5 to "°C", UnitConversion.convert(21.5, "°C", UnitSystem.METRIC))
        assertEquals(21.5 to "°C", UnitConversion.convert(21.5, "°C", UnitSystem.AUTO))
        assertEquals(21.5, UnitFormatter(UnitSystem.AUTO).value(21.5, "°C"))
    }

    @Test
    fun nullStaysNull() {
        val f = UnitFormatter(imperial)
        assertNull(f.value(null as Double?, "°C"))
        assertNull(f.value(null as Float?, "°C"))
        close(77.0, f.value(25.0, "°C"))
        assertEquals(1, f.decimals("°C"))
    }

    @Test
    fun autoResolvesFromRegion() {
        assertEquals(UnitSystem.IMPERIAL, UnitSystem.AUTO.resolve("US"))
        assertEquals(UnitSystem.IMPERIAL, UnitSystem.AUTO.resolve("us"))
        assertEquals(UnitSystem.IMPERIAL, UnitSystem.AUTO.resolve("LR"))
        assertEquals(UnitSystem.IMPERIAL, UnitSystem.AUTO.resolve("MM"))
        assertEquals(UnitSystem.METRIC, UnitSystem.AUTO.resolve("GB"))
        assertEquals(UnitSystem.METRIC, UnitSystem.AUTO.resolve("DE"))
        assertEquals(UnitSystem.METRIC, UnitSystem.AUTO.resolve(null))
        assertEquals(UnitSystem.METRIC, UnitSystem.METRIC.resolve("US"))
        assertEquals(UnitSystem.IMPERIAL, UnitSystem.IMPERIAL.resolve("DE"))
        assertTrue(systemUnitSystem() != UnitSystem.AUTO)
    }

    @Test
    fun parsesStoredNames() {
        assertEquals(UnitSystem.IMPERIAL, UnitSystem.parse("IMPERIAL"))
        assertEquals(UnitSystem.AUTO, UnitSystem.parse(null))
        assertEquals(UnitSystem.AUTO, UnitSystem.parse("bogus"))
    }

    @Test
    fun fixedDecimals() {
        assertEquals("70.7", formatFixed(70.7, 1))
        assertEquals("-3", formatFixed(-3.0, 0))
        assertEquals("29.92", formatFixed(29.921, 2))
        assertEquals("0", formatFixed(-0.01, 0))
    }
}
