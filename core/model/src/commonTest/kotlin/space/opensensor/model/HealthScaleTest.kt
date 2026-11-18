package space.opensensor.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class HealthScaleTest {
    private fun steps(edges: List<Double>, vararg en: String) =
        en.mapIndexed { i, label -> ScaleStep(edges.getOrNull(i), LocalizedText(mapOf("en" to label, "ar" to "$label ar"))) }

    @Test
    fun aStepIsNamedInTheAskedLanguage() {
        val edges = listOf(10.0, 20.0)
        val scale = HealthScale("p", "u", edges, "s", steps(edges, "low", "mid", "high"))
        assertEquals("low", scale.stepName(10.0, "en"))
        assertEquals("mid ar", scale.stepName(10.5, "ar"))
        assertEquals("high", scale.stepName(99.0, "fr"))
        assertNull(scale.stepName(Double.NaN, "en"))
    }

    @Test
    fun aScaleWithoutNamesNamesNoStep() {
        assertNull(HealthScale("p", "u", listOf(10.0), "s").stepName(5.0, "en"))
    }

    @Test
    fun aScaleNamesEveryStepOrNone() {
        val edges = listOf(10.0, 20.0)
        assertFailsWith<IllegalArgumentException> { HealthScale("p", "u", edges, "s", steps(edges, "low", "high")) }
    }
}
