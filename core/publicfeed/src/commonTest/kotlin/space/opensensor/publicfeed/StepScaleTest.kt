package space.opensensor.publicfeed

import space.opensensor.model.LocalizedText
import space.opensensor.model.Scale
import space.opensensor.model.ScaleStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StepScaleTest {
    private fun step(upper: Double?, en: String = "s$upper", hint: String? = null) =
        ScaleStep(upper, LocalizedText(mapOf("en" to en, "ar" to "ع$en")), hint)

    private fun scale(vararg steps: ScaleStep, source: String = "https://example.test/soil", guideline: Double? = null) =
        Scale("soil", source, steps.toList(), "m³/m³", guideline)

    @Test
    fun aScaleWithoutHintsTakesTheNeutralRampEndToEnd() {
        val s = StepScale.of("soil_moisture", scale(step(0.1), step(0.3), step(null)))!!
        assertEquals(listOf(SequentialRamp.at(0.0), SequentialRamp.at(0.5), SequentialRamp.at(1.0)), s.colors)
        assertEquals(listOf(0.1, 0.3), s.edges)
        assertNull(s.who)
        assertNull(s.guideline)
        assertEquals("s0.1", s.label(0, "fr"))
        assertEquals("عs0.1", s.label(0, "ar"))
    }

    @Test
    fun stepsReadUpperInclusiveAndAClosedTopClampsToTheLastStep() {
        val s = StepScale.of("soil_moisture", scale(step(0.1), step(0.3, hint = "112233")))!!
        assertEquals(0, s.stepOf(0.1))
        assertEquals(1, s.stepOf(0.1000001))
        assertEquals(1, s.stepOf(0.3))
        assertEquals(1, s.stepOf(9.0))
        assertEquals(0, s.stepOf(-5.0))
        assertNull(s.stepOf(Double.NaN))
        assertNull(s.stepOf(Double.POSITIVE_INFINITY))
        assertEquals(0xFF112233.toInt(), s.colorOf(0.2))
        assertEquals(null to 0.1, s.rangeOf(0))
        assertEquals(0.1 to 0.3, s.rangeOf(1))
        assertTrue(s.darkFill(1))
    }

    @Test
    fun aScaleOutOfOrderIsRefused() {
        assertNull(StepScale.of("x", scale(step(0.3), step(0.1), step(null))))
        assertNull(StepScale.of("x", scale(step(0.3), step(0.3))))
        assertNull(StepScale.of("x", scale(step(0.1), step(null), step(0.5))))
        assertNull(StepScale.of("x", scale(step(Double.NaN), step(null))))
        assertNotNull(StepScale.of("x", scale(step(null))))
    }

    @Test
    fun aScaleTooLongOrOutOfBoundsIsRefused() {
        val max = StepScale.MAX_STEPS
        assertNotNull(StepScale.of("x", scale(*(1 until max).map { step(it.toDouble()) }.plus(step(null)).toTypedArray())))
        assertNull(StepScale.of("x", scale(*(1..max).map { step(it.toDouble()) }.plus(step(null)).toTypedArray())))
        val m = StepScale.MAX_MAGNITUDE
        assertNotNull(StepScale.of("x", scale(step(-m), step(m), step(null), guideline = m)))
        for (bad in listOf(m * 2, -m * 2, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertNull(StepScale.of("x", scale(step(0.0), step(bad), step(null))), "edge $bad")
            assertNull(StepScale.of("x", scale(step(1.0), step(null), guideline = bad)), "guideline $bad")
        }
        // The last step may be closed, and then it is bounded too.
        assertNull(StepScale.of("x", scale(step(1.0), step(Double.POSITIVE_INFINITY))))
    }

    @Test
    fun binsStayBoundedForAHugeTop() {
        val s = StepScale.of("pm25", DiscoverCollection.BUNDLED.scale("pm25")!!)!!
        // The top comes from the data, not the scale. 2e15 half units would never finish if every
        // width were tried, the 25 bin counts answer at once.
        assertNull(StepAxis.bins(s, 1e15))
        assertEquals(30, StepAxis.bins(s, 75.0))
    }

    @Test
    fun aTopTooSmallForOneUnitHasNoBins() {
        val s = StepScale.of("pm25", DiscoverCollection.BUNDLED.scale("pm25")!!)!!
        // Just inside the rounding slack of zero half units, so the top rounds to zero and the bin
        // search would divide by it.
        assertNull(StepAxis.bins(s, 4.9e-10))
    }

    @Test
    fun onlyASixStepOpenScaleCitedToWhoIsTheMoodScale() {
        val pm = DiscoverCollection.BUNDLED.scale("pm25")!!
        assertNotNull(StepScale.of("pm25", pm)!!.who)
        assertNull(StepScale.of("pm25", pm.copy(source = "https://example.test/other"))!!.who)
        assertNull(StepScale.of("pm25", pm.copy(steps = pm.steps.dropLast(1)))!!.who)
        assertNull(StepScale.of("pm25", pm.copy(steps = pm.steps.dropLast(1) + step(500.0)))!!.who)
        // Five steps cited to WHO with an open last step is not the WHO scale either.
        val five = pm.copy(steps = pm.steps.dropLast(2) + pm.steps.last())
        assertEquals(5, five.steps.size)
        assertNull(five.steps.last().upper)
        assertNull(StepScale.of("pm25", five)!!.who)
        assertFalse(StepScale.of("pm25", pm)!!.darkFill(0))
    }
}
