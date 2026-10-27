package space.opensensor.data.history

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The calendar windows on every platform (the JVM tests also check them against java.time). Expected
 * instants come from Python `zoneinfo` for America/New_York.
 */
class CalendarWindowTest {
    private val newYork = TimeZone.of("America/New_York")

    @Test
    fun spring_and_fall_local_days_have_23_and_25_hours() {
        val spring = HistoryWindow.endingOn(LocalDate(2026, 3, 9), HistoryRange.DAY, newYork)
        assertEquals(1_772_946_000L, spring.fromS)
        assertEquals(1_773_028_800L, spring.toS)
        assertEquals(23 * 3_600L, spring.seconds)
        val fall = HistoryWindow.dayContaining(1_793_505_600L + 7_200, newYork)
        assertEquals(1_793_505_600L, fall.fromS)
        assertEquals(1_793_595_600L, fall.toS)
        assertEquals(fall, fall.page(HistoryRange.DAY, -1).page(HistoryRange.DAY, 1))
    }

    @Test
    fun local_days_and_monday_weeks_follow_the_zone() {
        val week = HistoryWindow.endingOn(LocalDate(2026, 3, 12), HistoryRange.WEEK, newYork) // Thu 5 to Thu 12 March
        val series = HistorySeries(AirMetrics.PM25, HistoryRange.WEEK, week.fromS, week.toS, emptyList(), validRows = 0, expectedRows = 0, mean = null, max = null, binned = false)
        val days = Columns.rebinLocalDays(series, newYork)
        assertEquals(7, days.size)
        assertEquals(listOf(24, 24, 24, 23, 24, 24, 24), days.map { ((it.endS - it.startS) / 3_600).toInt() })
        val weeks = Columns.rebinLocalWeeks(series, newYork)
        assertEquals(listOf(week.fromS, 1_773_028_800L), weeks.map { it.startS }) // split at Monday 9 March
    }
}
