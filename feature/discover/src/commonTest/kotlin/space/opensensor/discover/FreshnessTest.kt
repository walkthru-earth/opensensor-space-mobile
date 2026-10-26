package space.opensensor.discover

import kotlinx.datetime.LocalDate
import space.opensensor.publicfeed.CellRow
import space.opensensor.publicfeed.CellStat
import space.opensensor.publicfeed.FeedProperty
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
        // An older thing in the same partition shows its own day, so a 6 day old value is not passed off as current.
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

    private val endOf = { d: Int -> LocalDate(2026, 10, d + 1).toEpochDays() * 86_400_000L }
    private val pm25 = FeedProperty("pm25", "PM2.5", "µg/m³", "Air")
    private val stat = CellStat(3, 12.0, null, null)

    /** A daily cell updated on 8 Oct, its PM2.5 dated by [pmDay] when set, measuring PM2.5 when [measured]. */
    private fun row(cell: Long, pmDay: Int?, measured: Boolean = true) = CellRow(
        cell,
        devices = 3,
        updatedEpochMs = endOf(8),
        stats = if (measured) mapOf("pm25" to stat) else mapOf("no2" to stat),
        updatedByProperty = pmDay?.let { mapOf("pm25" to endOf(it)) }.orEmpty(),
    )

    private fun daily(rows: List<CellRow>) = DiscoverUiState(daily = true, dataDate = LocalDate(2026, 10, 8), property = pm25, rows = rows, newestUpdateEpochMs = endOf(8))

    @Test
    fun theDetailedSheetDatesTheCellByTheShownPropertysOwnDay() {
        // The cell has something from 8 Oct, but its PM2.5 is from 5 Oct.
        val cell = row(1L, pmDay = 5)
        assertEquals(Freshness.DataTo(LocalDate(2026, 10, 5)), daily(listOf(cell)).cellFreshness(cell, endOf(8)))
    }

    @Test
    fun theMapCaptionNamesTheNewestDayOfTheShownProperty() {
        // Other properties run to 8 Oct, PM2.5 only to 6 Oct, and a cell without PM2.5 does not count.
        val s = daily(listOf(row(1L, pmDay = 5), row(2L, pmDay = 6), row(3L, pmDay = null, measured = false)))
        assertEquals(Freshness.DataTo(LocalDate(2026, 10, 6)), s.inViewFreshness(endOf(8)))
    }
}
