package earth.walkthru.aqsync.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VocabularyTest {
    @Test
    fun equalsGateComparesNumbersByValue() {
        val gate = ValidityGate.Equals("pms_status", GateValue.Number(4.0))
        assertTrue(gate.accepts(4))
        assertTrue(gate.accepts(4L))
        assertTrue(gate.accepts(4.0))
        assertFalse(gate.accepts(3L))
        assertFalse(gate.accepts(null))
        assertFalse(gate.accepts("4"))
    }

    @Test
    fun equalsGateComparesTextAndFlags() {
        assertTrue(ValidityGate.Equals("m", GateValue.Text("run")).accepts("run"))
        assertFalse(ValidityGate.Equals("m", GateValue.Text("run")).accepts("stop"))
        assertTrue(ValidityGate.Equals("ok", GateValue.Flag(true)).accepts(true))
        assertFalse(ValidityGate.Equals("ok", GateValue.Flag(true)).accepts(1))
    }

    @Test
    fun finiteGatePassesOnlyFiniteNumbers() {
        val gate = ValidityGate.Finite("imu_temperature_c")
        assertTrue(gate.accepts(21.5))
        assertTrue(gate.accepts(7L))
        assertFalse(gate.accepts(Double.NaN))
        assertFalse(gate.accepts(Double.POSITIVE_INFINITY))
        assertFalse(gate.accepts(null))
    }

    @Test
    fun scaleStepLookupIsInclusiveOfUpperBounds() {
        val scale = Scale(
            id = "s",
            source = "https://example.org",
            steps = listOf(
                ScaleStep(5.0, LocalizedText.of("low")),
                ScaleStep(10.0, LocalizedText.of("mid")),
                ScaleStep(null, LocalizedText.of("high")),
            ),
        )
        assertEquals(0, scale.stepIndexOf(0.0))
        assertEquals(0, scale.stepIndexOf(5.0))
        assertEquals(1, scale.stepIndexOf(5.01))
        assertEquals(1, scale.stepIndexOf(10.0))
        assertEquals(2, scale.stepIndexOf(1e6))
        assertEquals(-1, scale.stepIndexOf(Double.NaN))
        assertEquals("high", scale.stepOf(99.0)?.label?.get("en"))
        assertNull(scale.stepOf(Double.NaN))
    }

    @Test
    fun closedScaleHasNoStepAboveItsLastBound() {
        val scale = Scale("s", "https://example.org", listOf(ScaleStep(100.0, LocalizedText.of("ok"))))
        assertEquals(0, scale.stepIndexOf(100.0))
        assertNull(scale.stepOf(100.5))
    }

    @Test
    fun localizedTextFallsBackToEnglishThenPlain() {
        val both = LocalizedText(mapOf("en" to "Battery", "ar" to "البطارية"))
        assertEquals("البطارية", both.get("ar"))
        assertEquals("Battery", both.get("fr"))
        assertEquals("Battery", LocalizedText.of("Battery").get("ar"))
        assertEquals("x", LocalizedText(mapOf("de" to "x")).get("ar"))
    }

    @Test
    fun aggregateSetIsOpen() {
        assertTrue(Aggregate("mean").isKnown)
        assertEquals(Aggregate.MEAN, Aggregate("mean").rollup)
        assertFalse(Aggregate("median").isKnown)
        assertEquals(Aggregate.POINT, Aggregate("median").rollup)
    }

    @Test
    fun plausibleRangeContains() {
        val range = PlausibleRange(-20.0, 85.0)
        assertTrue(-20.0 in range)
        assertFalse(85.1 in range)
        assertFailsWith<IllegalArgumentException> { PlausibleRange(2.0, 1.0) }
    }
}
