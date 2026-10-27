package space.opensensor.family.air

import space.opensensor.data.history.HistoryPoint
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PmNowCastTest {
    private fun assertNowCast(expected: Double, got: Double?) {
        assertTrue(got != null && abs(got - expected) < 1e-9, "expected $expected got $got")
    }

    /**
     * The worked PM example AirNow publishes (forum.airnowtech.org/t/the-nowcast-for-ozone-and-pm/172),
     * twelve hours with the second most recent one missing. The range is 48.2 over a max of 69.2, so
     * the weight floors at 0.5, and the NowCast is 28.4 µg/m³.
     */
    @Test
    fun matchesTheAirNowWorkedExampleWithAMissingHour() {
        val hours = listOf(21.0, null, 35.0, 49.2, 48.6, 53.7, 66.2, 69.2, 64.9, 50.0, 43.0, 34.9)
        assertNowCast(28.4, PmNowCast.of(hours))
    }

    @Test
    fun needsTwoOfTheThreeMostRecentHours() {
        assertNull(PmNowCast.of(listOf(21.0, null, null, 49.2, 48.6, 53.7)))
        assertNull(PmNowCast.of(listOf(null, null, 35.0, 49.2)))
        assertNull(PmNowCast.of(emptyList()))
        assertNull(PmNowCast.of(listOf(Double.NaN, 20.0, Double.POSITIVE_INFINITY)), "a non-finite hour is missing")
        assertNowCast(18.2, PmNowCast.of(listOf(null, 20.0, 16.0)))
    }

    /** A steady air gives a weight above the floor, 12 over 20 is 0.6. */
    @Test
    fun usesTheMinOverMaxWeightAboveTheFloor() {
        // (20 + 0.6 x 16 + 0.36 x 12) / (1 + 0.6 + 0.36) = 17.306
        assertNowCast(17.3, PmNowCast.of(listOf(20.0, 16.0, 12.0)))
        // (20 + 0.8 x 16) / 1.8 = 18.222
        assertNowCast(18.2, PmNowCast.of(listOf(20.0, 16.0)))
    }

    @Test
    fun readsOnlyTwelveHours() {
        val twelve = listOf(21.0, null, 35.0, 49.2, 48.6, 53.7, 66.2, 69.2, 64.9, 50.0, 43.0, 34.9)
        assertNowCast(28.4, PmNowCast.of(twelve + listOf(0.0, 500.0)))
    }

    @Test
    fun truncatesToTheStepWithoutLosingAnExactValue() {
        assertNowCast(28.4, PmNowCast.of(listOf(28.4, 28.4, 28.4)))
        assertNowCast(28.4, PmNowCast.of(listOf(28.49, 28.49)))
        // PM10 truncates to whole µg/m³. (55.7 + 0.718 x 40) / 1.718 = 49.14
        assertNowCast(49.0, PmNowCast.of(listOf(55.7, 40.0), PmNowCast.PM10_STEP))
    }

    /** Every tenth from 0 to 100 comes back as the exact decimal, so a WHO edge such as 15 stays FRESH. */
    @Test
    fun aTruncatedValueIsTheExactDecimal() {
        (0..1_000).forEach { k ->
            val tenth = k / 10.0
            assertEquals(tenth, PmNowCast.of(listOf(tenth, tenth)), "$tenth")
        }
        assertEquals(AirMood.FRESH, AirMood.of(PmNowCast.of(listOf(15.0, 15.0))!!))
    }

    @Test
    fun cleanAirOfZeroStaysZero() {
        assertNowCast(0.0, PmNowCast.of(listOf(0.0, 0.0, 0.0)))
    }

    @Test
    fun hourlySlotsEndBeforeTheRunningHour() {
        val now = 1_760_000_400L // a whole UTC hour
        fun hour(start: Long, mean: Float?) = HistoryPoint(start, start + 3_600, mean, null, null, 1f)
        val points = listOf(
            hour(now - 12 * 3_600, 9f),
            hour(now - 3 * 3_600, null),
            hour(now - 2 * 3_600, 14f),
            hour(now - 3_600, 12f),
            hour(now, 99f), // the hour still running
            HistoryPoint(now - 3_600 + 60, now - 3_600 + 120, 50f, null, null, 1f), // a raw row, not an hour
        )
        val slots = PmNowCast.hourlySlots(points, now)
        assertEquals(PmNowCast.HOURS, slots.size)
        assertEquals(listOf(12.0, 14.0, null), slots.take(3))
        assertEquals(9.0, slots[11])
        assertEquals(9, slots.count { it == null })
    }

    @Test
    fun hourStartFloorsToTheUtcHour() {
        assertEquals(1_760_000_400L, PmNowCast.hourStart(1_760_000_400L + 3_599))
        assertEquals(1_760_000_400L, PmNowCast.hourStart(1_760_000_400L))
    }
}
