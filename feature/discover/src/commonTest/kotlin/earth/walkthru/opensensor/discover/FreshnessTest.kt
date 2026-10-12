package earth.walkthru.opensensor.discover

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FreshnessTest {
    private val hour = 3_600_000L
    private val endOf8Oct = LocalDate(2026, 10, 9).toEpochDays() * 86_400_000L

    @Test
    fun aDailyValueIsNamedByItsDayNeverByHoursOnTheClock() {
        val asOf = LocalDate(2026, 10, 8)
        assertEquals(Freshness.DataTo(LocalDate(2026, 10, 8)), freshnessOf(endOf8Oct, endOf8Oct + 98 * hour, asOf))
        // An older station in the same partition shows its own day, so a 6 day old value is not passed off as current.
        assertEquals(Freshness.DataTo(LocalDate(2026, 10, 3)), freshnessOf(endOf8Oct - 5 * 24 * hour, endOf8Oct, asOf))
    }

    @Test
    fun anHourlyValueReadsInHoursAndGoesStaleAfterThree() {
        assertEquals(Freshness.Recent, freshnessOf(endOf8Oct, endOf8Oct + hour / 2, null))
        assertEquals(Freshness.HoursAgo(2), freshnessOf(endOf8Oct, endOf8Oct + 2 * hour, null))
        assertEquals(Freshness.Stale, freshnessOf(endOf8Oct, endOf8Oct + 3 * hour, null))
        assertEquals(Freshness.HoursAgo(98), freshnessOf(endOf8Oct, endOf8Oct + 98 * hour, null, staleShown = false))
    }

    @Test
    fun noUpdateHasNoFreshness() {
        assertNull(freshnessOf(null, 0L, null))
    }
}
