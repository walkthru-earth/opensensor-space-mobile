package earth.walkthru.opensensor.history

import earth.walkthru.aqsync.data.history.ChartAxis
import earth.walkthru.aqsync.units.UnitSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class YAxisTicksTest {
    @Test
    fun metricKeepsTheSiMinToMaxByStepTicks() {
        val axis = ChartAxis(0f, 60f, 20f)
        val ticks = yAxisTicks(axis, "µg/m³", UnitSystem.METRIC)
        assertEquals(listOf(0f to "0", 20f to "20", 40f to "40", 60f to "60"), ticks)
        // A unit with no conversion is identical under imperial.
        assertEquals(ticks, yAxisTicks(axis, "µg/m³", UnitSystem.IMPERIAL))
        assertEquals(listOf(-10f to "-10", 0f to "0", 10f to "10", 20f to "20"), yAxisTicks(ChartAxis(-10f, 20f, 10f), "°C", UnitSystem.METRIC))
        assertEquals(listOf("0", "2.5", "5", "7.5"), yAxisTicks(ChartAxis(0f, 7.5f, 2.5f), "°C", UnitSystem.METRIC).map { it.second })
    }

    @Test
    fun imperialTemperatureLabelsAreRoundFahrenheitPlacedOnTheCelsiusScale() {
        val ticks = yAxisTicks(ChartAxis(0f, 40f, 10f), "°C", UnitSystem.IMPERIAL)
        assertEquals(listOf("40", "60", "80", "100"), ticks.map { it.second })
        val expected = listOf(40.0, 60.0, 80.0, 100.0).map { ((it - 32.0) * 5.0 / 9.0).toFloat() }
        ticks.map { it.first }.zip(expected).forEach { (a, e) -> assertEquals(e, a, 1e-4f) }
        ticks.forEach { assertTrue(it.first in 0f..40f) }
    }

    @Test
    fun imperialPressureLabelsCarryTheDecimalsOfTheStep() {
        val ticks = yAxisTicks(ChartAxis(990f, 1030f, 10f), "hPa", UnitSystem.IMPERIAL)
        ticks.forEach { assertTrue(it.first in 990f..1030f) }
        // 29.23 to 30.42 inHg at about five ticks steps by 0.25.
        assertEquals(listOf("29.25", "29.50", "29.75", "30.00", "30.25"), ticks.map { it.second })
    }

    @Test
    fun valuesAndAxisLabelsNeedNoPlatformFormatter() {
        assertEquals("12", fmtAxis(12f))
        assertEquals("12.5", fmtAxis(12.5f))
        assertEquals("172", fmtValue(172.4f))
        assertEquals("9.0", fmtValue(9f))
    }
}
