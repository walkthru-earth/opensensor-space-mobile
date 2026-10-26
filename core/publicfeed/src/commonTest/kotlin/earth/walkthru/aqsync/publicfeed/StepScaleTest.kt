package earth.walkthru.aqsync.publicfeed

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
    fun onlyASixStepOpenScaleCitedToWhoIsTheMoodScale() {
        val pm = DiscoverCollection.BUNDLED.scale("pm25")!!
        assertNotNull(StepScale.of("pm25", pm)!!.who)
        assertNull(StepScale.of("pm25", pm.copy(source = "https://example.test/other"))!!.who)
        assertNull(StepScale.of("pm25", pm.copy(steps = pm.steps.dropLast(1)))!!.who)
        assertNull(StepScale.of("pm25", pm.copy(steps = pm.steps.dropLast(1) + step(500.0)))!!.who)
        assertFalse(StepScale.of("pm25", pm)!!.darkFill(0))
    }
}
