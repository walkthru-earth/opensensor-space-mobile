package space.opensensor.family.air

import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WhoGuidelinesTest {
    init {
        AirFamily.install()
    }

    @Test
    fun pm25EdgesAreWho2021TwentyFourHourValues() {
        assertEquals(listOf(15.0, 25.0, 37.5, 50.0, 75.0), WhoGuidelines.PM25.edges)
        assertEquals(15.0, WhoGuidelines.PM25.guideline)
    }

    @Test
    fun pm10EdgesAreWho2021TwentyFourHourValues() {
        assertEquals(listOf(45.0, 50.0, 75.0, 100.0, 150.0), WhoGuidelines.PM10.edges)
    }

    @Test
    fun anEdgeValueMeetsItsStep() {
        val s = WhoGuidelines.PM25
        assertEquals(WhoStep.GUIDELINE, s.stepOf(0.0))
        assertEquals(WhoStep.GUIDELINE, s.stepOf(15.0))
        assertEquals(WhoStep.IT4, s.stepOf(15.1))
        assertEquals(WhoStep.IT4, s.stepOf(25.0))
        assertEquals(WhoStep.IT3, s.stepOf(37.5))
        assertEquals(WhoStep.IT2, s.stepOf(50.0))
        assertEquals(WhoStep.IT1, s.stepOf(75.0))
        assertEquals(WhoStep.ABOVE_IT1, s.stepOf(75.1))
        assertNull(s.stepOf(Double.NaN))
    }

    @Test
    fun propertiesWithoutAWhoValueHaveNoScale() {
        assertNull(WhoGuidelines.forProperty("co2"))
        assertNull(WhoGuidelines.forProperty("temperature"))
        assertEquals(WhoGuidelines.PM10, WhoGuidelines.forProperty("pm10"))
    }

    @Test
    fun rangesCoverTheLine() {
        val s = WhoGuidelines.PM25
        assertEquals(null to 15.0, s.rangeOf(WhoStep.GUIDELINE))
        assertEquals(15.0 to 25.0, s.rangeOf(WhoStep.IT4))
        assertEquals(75.0 to null, s.rangeOf(WhoStep.ABOVE_IT1))
    }
}

class WhoPaletteTest {
    private fun linear(argb: Int, shift: Int): Double {
        val c = ((argb ushr shift) and 0xFF) / 255.0
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(argb: Int) = 0.2126 * linear(argb, 16) + 0.7152 * linear(argb, 8) + 0.0722 * linear(argb, 0)

    @Test
    fun stepsRunLightToDarkSoTheOrderSurvivesGreyscale() {
        val l = WhoStep.entries.map { luminance(it.color) }
        assertTrue(l.zipWithNext().all { (a, b) -> a > b }, "luminance must fall step by step, was $l")
    }

    @Test
    fun darkFillMatchesContrast() {
        // Dark ink (#14181B) reads on light steps and white on dark steps, at WCAG large text 3:1 or better.
        val ink = luminance(0xFF14181B.toInt())
        WhoStep.entries.forEach { step ->
            val l = luminance(step.color)
            val onDark = (1.05) / (l + 0.05)
            val onLight = (l + 0.05) / (ink + 0.05)
            if (step.darkFill) assertTrue(onDark >= 3.0, "$step white text") else assertTrue(onLight >= 3.0, "$step dark text")
        }
    }
}
