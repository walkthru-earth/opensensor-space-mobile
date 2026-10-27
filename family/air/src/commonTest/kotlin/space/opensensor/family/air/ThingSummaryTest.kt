package space.opensensor.family.air

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThingSummaryTest {
    @Test
    fun pmStepsFollowTheWhoEdgesAtOrBelow() {
        val s = ThingSummary.of("pm25", "µg/m³", value = 15.0, updatedEpochMs = 1_000L, history = listOf(15.0, 15.1, 80.0))
        assertTrue(s.scaled)
        assertEquals(15.0, s.value)
        assertEquals(WhoStep.GUIDELINE, s.step)
        assertEquals(listOf(WhoStep.GUIDELINE, WhoStep.IT4, WhoStep.ABOVE_IT1), s.blocks.map { it.step })
        assertEquals(listOf(1f, 1f, 1f), s.blocks.map { it.weight })
        assertEquals(1_000L, s.updatedEpochMs)
    }

    @Test
    fun pm10UsesItsOwnEdges() {
        val s = ThingSummary.of("pm10", "µg/m³", value = 46.0, updatedEpochMs = null, history = emptyList())
        assertEquals(WhoStep.IT4, s.step)
        assertTrue(s.blocks.isEmpty())
    }

    @Test
    fun missingAndNonFiniteValuesStayNull() {
        val s = ThingSummary.of("pm25", "µg/m³", value = Double.NaN, updatedEpochMs = null, history = listOf(null, Double.NaN, Double.POSITIVE_INFINITY, 10.0))
        assertNull(s.value)
        assertNull(s.step)
        assertTrue(s.scaled)
        assertEquals(listOf(null, null, null, WhoStep.GUIDELINE), s.blocks.map { it.step })
    }

    @Test
    fun anUnscaledPropertyHasNoMood() {
        val s = ThingSummary.of("co2", "ppm", value = 2_000.0, updatedEpochMs = 5L, history = listOf(400.0, 2_000.0, 5_000.0))
        assertFalse(s.scaled)
        assertEquals(2_000.0, s.value)
        assertNull(s.step)
        assertTrue(s.blocks.isEmpty())
    }

    @Test
    fun anUnscaledPropertyWithoutAValueStaysNotAvailable() {
        val s = ThingSummary.of("co2", "ppm", value = null, updatedEpochMs = 5L, history = listOf(null, null, null))
        assertFalse(s.scaled)
        assertNull(s.value)
        assertNull(s.step)
        assertTrue(s.blocks.isEmpty())
    }
}
