package space.opensensor.devices

import space.opensensor.data.history.LatestDay
import space.opensensor.data.history.MetricSpec
import space.opensensor.family.air.AirFamily
import space.opensensor.family.air.AirMetrics
import space.opensensor.family.air.stepOf
import space.opensensor.units.UnitFormatter
import space.opensensor.units.UnitSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DayLineTest {
    init {
        AirFamily.install()
    }

    private fun day(metric: MetricSpec, mean: Float) = LatestDay(86_400, mean, 1f, metric)

    @Test
    fun `a banded metric shows its WHO step and a mean without one shows no line`() {
        val line = dayLine(day(AirMetrics.PM25_CORRECTED, 12.34f), UnitFormatter.Metric)
        assertEquals(DayLine("12.3", AirMetrics.PM25_CORRECTED.unit, AirMetrics.PM25_CORRECTED.stepOf(12.34)), line)
        assertNotNull(line?.step, "a PM2.5 day always carries its WHO step")
        assertNull(dayLine(day(AirMetrics.PM25, Float.NaN), UnitFormatter.Metric))
    }

    @Test
    fun `a metric without a scale shows a neutral line at its decimals`() {
        val moisture = MetricSpec(id = "soil_moisture", column = "soil_moisture", label = "Soil moisture", unit = "m³/m³", sourceUnit = "m3/m3", primary = true, decimals = 2)
        assertEquals(DayLine("0.31", "m³/m³", null), dayLine(day(moisture, 0.3149f), UnitFormatter.Metric))
        assertEquals(DayLine("25", "m³/m³", null), dayLine(day(moisture.copy(decimals = 0), 24.6f), UnitFormatter.Metric))
    }

    @Test
    fun `a unit the owner's system converts shows converted with its own decimals`() {
        val temp = MetricSpec(id = "soil_temperature_c", column = "soil_temperature_c", label = "Soil temperature", unit = "°C", sourceUnit = "Cel", decimals = 1)
        assertEquals(DayLine("21.5", "°C", null), dayLine(day(temp, 21.5f), UnitFormatter.Metric))
        assertEquals(DayLine("70.7", "°F", null), dayLine(day(temp, 21.5f), UnitFormatter(UnitSystem.IMPERIAL)))
    }

    @Test
    fun `a unit with a slash keeps a word joiner after it so a wrap never splits it`() {
        assertEquals("µg/\u2060m³", wholeUnit("µg/m³"))
        assertEquals("°C", wholeUnit("°C"))
    }

    @Test
    fun `the day line keeps the plain unit for a screen reader`() {
        val line = dayLine(day(AirMetrics.PM25_CORRECTED, 12.34f), UnitFormatter.Metric)
        assertEquals("µg/m³", line?.unit)
    }
}
