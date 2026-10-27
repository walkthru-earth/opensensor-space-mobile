package space.opensensor.publicfeed

import kotlinx.datetime.LocalDate
import space.opensensor.family.air.AirFamily
import space.opensensor.family.air.PublicSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DailyCellFoldTest {
    init {
        AirFamily.install()
    }

    private val asOf = LocalDate(2026, 10, 8)
    private val a = 0x85283473fffffffL
    private val b = 0x8528347bfffffffL

    private fun day(d: Int) = LocalDate(2026, 10, d)

    private fun stat(n: Int?, mean: Double?, min: Double? = mean, max: Double? = mean) = CellStat(n, mean, min, max)

    private fun row(h3: Long, date: LocalDate, devices: Int, vararg stats: Pair<String, CellStat>) =
        CellRow(h3, devices, endOf(date), mapOf(*stats), date)

    private fun endOf(date: LocalDate): Long = (date.toEpochDays() + 1) * 86_400_000L

    @Test
    fun `day takes the newest day in range per property`() {
        val rows = listOf(
            row(a, day(5), 2, "pm25" to stat(20, 10.0, 4.0, 18.0), "temperature" to stat(20, 20.0)),
            row(a, day(7), 3, "pm25" to stat(22, 12.0, 5.0, 19.0)),
        )
        val out = DailyCellFold.fold(rows, FeedWindow.DAY, asOf).single()
        assertEquals(a, out.h3)
        assertEquals(stat(22, 12.0, 5.0, 19.0), out.stats["pm25"])
        // Temperature was last measured on the 5th, so its newest day in range is the 5th.
        assertEquals(stat(20, 20.0), out.stats["temperature"])
        assertEquals(3, out.devices)
        assertEquals(endOf(day(7)), out.updatedEpochMs)
        assertNull(out.date)
    }

    @Test
    fun `each property keeps the date of its own newest day`() {
        // PM last measured on the 3rd, temperature on the 8th. The newer temperature must not make
        // the old PM look current (#109).
        val rows = listOf(
            row(a, day(3), 2, "pm25" to stat(20, 10.0)),
            row(a, day(8), 2, "temperature" to stat(20, 21.0)),
        )
        listOf(FeedWindow.DAY, FeedWindow.WEEK).forEach { window ->
            val out = DailyCellFold.fold(rows, window, asOf).single()
            assertEquals(endOf(day(8)), out.updatedEpochMs, "$window")
            val windows = mapOf(FeedWindow.DAY to out)
            assertEquals(endOf(day(3)), PublicSummary.of(TestProperties.PM25, windows).updatedEpochMs, "$window")
            assertEquals(endOf(day(8)), PublicSummary.of(TestProperties.TEMPERATURE, windows).updatedEpochMs, "$window")
            // A property the cell does not report stays missing.
            assertNull(PublicSummary.of(TestProperties.HUMIDITY, windows).value, "$window")
        }
    }

    @Test
    fun `week averages daily means and takes extremes and the largest n skipping gap days`() {
        val rows = listOf(
            row(a, day(2), 1, "pm25" to stat(18, 10.0, 2.0, 30.0)),
            // A gap on the 3rd to the 5th: those days are absent, never counted as zero.
            row(a, day(6), 4, "pm25" to stat(24, 20.0, 6.0, 40.0), "humidity" to stat(24, 60.0, 50.0, 70.0)),
            row(a, day(8), 2, "pm25" to stat(20, 30.0, 1.0, 35.0)),
        )
        val out = DailyCellFold.fold(rows, FeedWindow.WEEK, asOf).single()
        assertEquals(stat(24, 20.0, 1.0, 40.0), out.stats["pm25"])
        // Humidity only on one day: its week is that day.
        assertEquals(stat(24, 60.0, 50.0, 70.0), out.stats["humidity"])
        assertEquals(4, out.devices)
        assertEquals(endOf(day(8)), out.updatedEpochMs)
    }

    @Test
    fun `days outside the seven days ending at asOf do not count`() {
        val rows = listOf(
            // The 1st is asOf minus 7, one day too old. The 9th is after asOf.
            row(a, day(1), 9, "pm25" to stat(24, 100.0)),
            row(a, day(2), 1, "pm25" to stat(18, 10.0)),
            row(a, day(9), 9, "pm25" to stat(24, 200.0)),
        )
        for (window in listOf(FeedWindow.DAY, FeedWindow.WEEK)) {
            val out = DailyCellFold.fold(rows, window, asOf).single()
            assertEquals(stat(18, 10.0), out.stats["pm25"], "$window")
            assertEquals(1, out.devices)
            assertEquals(endOf(day(2)), out.updatedEpochMs)
        }
    }

    @Test
    fun `asOf and asOf minus six are both inside the window`() {
        val rows = listOf(
            row(a, day(2), 1, "pm25" to stat(18, 10.0)),
            row(a, day(8), 1, "pm25" to stat(18, 20.0)),
        )
        assertEquals(15.0, DailyCellFold.fold(rows, FeedWindow.WEEK, asOf).single().mean("pm25"))
        assertEquals(20.0, DailyCellFold.fold(rows, FeedWindow.DAY, asOf).single().mean("pm25"))
        // One day later, the 2nd falls out.
        assertEquals(20.0, DailyCellFold.fold(rows, FeedWindow.WEEK, day(9)).single().mean("pm25"))
    }

    @Test
    fun `null stays null and a cell with nothing in range is dropped`() {
        val rows = listOf(
            // A stat with only n, or a mean without min and max: min and max stay null.
            row(a, day(7), 1, "pm25" to stat(18, 10.0, null, null), "pm10" to stat(5, null, null, null)),
            row(b, day(1), 1, "pm25" to stat(18, 10.0)),
            row(b, day(7), 1),
        )
        val out = DailyCellFold.fold(rows, FeedWindow.WEEK, asOf)
        assertEquals(listOf(a), out.map { it.h3 })
        assertEquals(stat(18, 10.0, null, null), out.single().stats["pm25"])
        assertNull(out.single().stats["pm10"], "a property without a mean is not measured")
        val day = DailyCellFold.fold(rows, FeedWindow.DAY, asOf).single()
        assertEquals(stat(18, 10.0, null, null), day.stats["pm25"])
        assertNull(day.stats["pm10"])
    }

    @Test
    fun `week skips null extremes and null n without making them zero`() {
        val rows = listOf(
            row(a, day(6), 1, "pm25" to stat(null, 10.0, null, 25.0)),
            row(a, day(7), 1, "pm25" to stat(null, 20.0, 15.0, null)),
        )
        assertEquals(stat(null, 15.0, 15.0, 25.0), DailyCellFold.fold(rows, FeedWindow.WEEK, asOf).single().stats["pm25"])
    }

    @Test
    fun `one thing keeps devices of one`() {
        val rows = listOf(row(a, day(8), 1, "pm25" to stat(24, 8.0)))
        for (window in listOf(FeedWindow.DAY, FeedWindow.WEEK)) {
            val out = DailyCellFold.fold(rows, window, asOf).single()
            assertEquals(1, out.devices)
            assertEquals(stat(24, 8.0), out.stats["pm25"])
        }
    }

    @Test
    fun `devices and updated come only from the days used`() {
        val rows = listOf(
            row(a, day(6), 2, "pm25" to stat(18, 10.0)),
            // A newer day with more things but no measured property is not used.
            row(a, day(8), 9, "pm25" to stat(null, null, null, null)),
        )
        for (window in listOf(FeedWindow.DAY, FeedWindow.WEEK)) {
            val out = DailyCellFold.fold(rows, window, asOf).single()
            assertEquals(2, out.devices)
            assertEquals(endOf(day(6)), out.updatedEpochMs)
        }
    }

    @Test
    fun `cells stay sorted while rows without a date and the last hour are ignored`() {
        val rows = listOf(
            row(b, day(8), 1, "pm25" to stat(1, 1.0)),
            row(a, day(8), 1, "pm25" to stat(1, 2.0)),
            CellRow(a, 5, 0L, mapOf("pm25" to stat(1, 99.0))),
        )
        val out = DailyCellFold.fold(rows, FeedWindow.DAY, asOf)
        assertEquals(listOf(a, b), out.map { it.h3 })
        assertEquals(2.0, out.first().mean("pm25"))
        assertTrue(DailyCellFold.fold(rows, FeedWindow.LAST_HOUR, asOf).isEmpty())
    }
}
