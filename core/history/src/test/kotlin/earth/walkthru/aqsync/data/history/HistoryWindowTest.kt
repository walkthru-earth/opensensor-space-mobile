package earth.walkthru.aqsync.data.history

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class HistoryWindowTest {
    private val newYork = ZoneId.of("America/New_York")

    @Test
    fun `spring local day has 23 hours and paging round trips at midnight`() {
        val spring = HistoryWindow.endingOn(LocalDate.of(2026, 3, 9), HistoryRange.DAY, newYork)
        assertEquals(LocalDate.of(2026, 3, 8).atStartOfDay(newYork).toEpochSecond(), spring.fromS)
        assertEquals(23 * 3_600L, spring.seconds)
        assertEquals(spring, spring.page(HistoryRange.DAY, -1).page(HistoryRange.DAY, 1))
    }

    @Test
    fun `fall local day has 25 hours and paging round trips at midnight`() {
        val fall = HistoryWindow.endingOn(LocalDate.of(2026, 11, 2), HistoryRange.DAY, newYork)
        assertEquals(LocalDate.of(2026, 11, 1).atStartOfDay(newYork).toEpochSecond(), fall.fromS)
        assertEquals(25 * 3_600L, fall.seconds)
        assertEquals(fall, fall.page(HistoryRange.DAY, -1).page(HistoryRange.DAY, 1))
    }

    @Test
    fun `week month and year retain exact local-date boundaries across transitions`() {
        for (range in listOf(HistoryRange.WEEK, HistoryRange.MONTH, HistoryRange.YEAR)) {
            val end = LocalDate.of(2026, 11, 4)
            val window = HistoryWindow.endingOn(end, range, newYork)
            assertEquals(end.minusDays(range.days).atStartOfDay(newYork).toEpochSecond(), window.fromS)
            assertEquals(end.atStartOfDay(newYork).toEpochSecond(), window.toS)
            assertEquals(window, window.page(range, -1).page(range, 1))
        }
    }
}
