package space.opensensor.history

import space.opensensor.data.history.HistoryColumn
import kotlin.test.Test
import kotlin.test.assertEquals

/** The calendar's screen reader buttons cover only days with a reading (P68). */
class CalendarSemanticsTest {
    private fun day(index: Int, mean: Float?) = HistoryColumn(
        startS = index * DAY,
        endS = (index + 1) * DAY,
        mean = mean,
        max = mean,
        coverage = if (mean == null) 0f else 1f,
        estimatedShare = 0f,
    )

    @Test
    fun aYearOfMostlyEmptyDaysListsOnlyTheDaysWithReadings() {
        // One column per day, as the day re-binning gives, with a mean on three of them.
        val year = (0 until 365).map { day(it, if (it in setOf(3, 100, 200)) 12f else null) }
        val readable = readableDays(year, fromS = 0, toS = 365 * DAY)
        assertEquals(listOf(3 * DAY, 100 * DAY, 200 * DAY), readable.keys.sorted())
    }

    @Test
    fun daysOutsideTheShownRangeAreLeftOut() {
        val days = listOf(day(0, 5f), day(1, 6f), day(2, 7f))
        val readable = readableDays(days, fromS = DAY, toS = 2 * DAY)
        assertEquals(listOf(DAY), readable.keys.toList())
    }

    private companion object {
        const val DAY = 86_400L
    }
}
