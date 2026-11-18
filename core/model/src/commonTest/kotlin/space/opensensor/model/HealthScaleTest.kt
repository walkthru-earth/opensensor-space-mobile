package space.opensensor.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class HealthScaleTest {
    private fun named(vararg en: String) = en.map { LocalizedText(mapOf("en" to it, "ar" to "$it ar")) }

    @Test
    fun aStepIsNamedInTheAskedLanguage() {
        val scale = HealthScale("p", "u", listOf(10.0, 20.0), "s", named("low", "mid", "high"))
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
        assertFailsWith<IllegalArgumentException> { HealthScale("p", "u", listOf(10.0, 20.0), "s", named("low", "high")) }
    }
}
