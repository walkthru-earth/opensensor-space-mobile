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

    private fun day(metric: MetricSpec, mean: Float, step: Double) = LatestDay(86_400, mean, 1f, metric, step)

    @Test
    fun `a banded metric shows its WHO step and a mean without one shows no line`() {
        val line = dayLine(day(AirMetrics.PM25_CORRECTED, 12.34f, 0.1), UnitFormatter.Metric)
        assertEquals(DayLine("12.3", AirMetrics.PM25_CORRECTED.unit, AirMetrics.PM25_CORRECTED.stepOf(12.34)), line)
        assertNotNull(line?.step, "a PM2.5 day always carries its WHO step")
        assertNull(dayLine(day(AirMetrics.PM25, Float.NaN, 0.1), UnitFormatter.Metric))
    }

    @Test
    fun `a metric without a scale shows a neutral line at its display step`() {
        val moisture = MetricSpec(id = "soil_moisture", column = "soil_moisture", label = "Soil moisture", unit = "m3/m3", sourceUnit = "m3/m3", primary = true)
        assertEquals(DayLine("0.31", "m3/m3", null), dayLine(day(moisture, 0.3149f, 0.01), UnitFormatter.Metric))
        assertEquals(DayLine("25", "m3/m3", null), dayLine(day(moisture, 24.6f, 5.0), UnitFormatter.Metric))
    }

    @Test
    fun `a unit the owner's system converts shows converted with its own decimals`() {
        val temp = MetricSpec(id = "soil_temperature_c", column = "soil_temperature_c", label = "Soil temperature", unit = "°C", sourceUnit = "Cel")
        assertEquals(DayLine("21.5", "°C", null), dayLine(day(temp, 21.5f, 0.1), UnitFormatter.Metric))
        assertEquals(DayLine("70.7", "°F", null), dayLine(day(temp, 21.5f, 0.1), UnitFormatter(UnitSystem.IMPERIAL)))
    }
}
