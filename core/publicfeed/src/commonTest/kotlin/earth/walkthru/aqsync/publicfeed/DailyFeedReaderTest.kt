package earth.walkthru.aqsync.publicfeed

import earth.walkthru.aqsync.data.history.parquet.MemoryRangeSource
import earth.walkthru.aqsync.publicfeed.OpenAqFixture.ACCRA
import earth.walkthru.aqsync.publicfeed.OpenAqFixture.CAIRO
import earth.walkthru.aqsync.publicfeed.OpenAqFixture.LAGOS
import earth.walkthru.aqsync.publicfeed.OpenAqFixture.NAIROBI
import earth.walkthru.aqsync.publicfeed.OpenAqFixture.endOf
import earth.walkthru.aqsync.publicfeed.OpenAqFixture.oct
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [ParquetFeedReader] over the daily layout of the OpenAQ aggregates, through a [MemoryRangeSource]. */
class DailyFeedReaderTest {
    private val bytes = fixtureBytes(OpenAqFixture.CELLS)

    private fun source() = MemoryRangeSource(bytes, OpenAqFixture.CELLS)

    @Test
    fun `every daily row keeps its date and its stations and null stays null`() = runTest {
        val table = ParquetFeedReader().readCells(source(), ranges = null)
        assertFalse(table.info.sample)
        assertEquals("1d", table.info.window)
        assertEquals(5, table.info.h3Res)
        assertEquals(listOf("pm25", "pm10", "pm1", "no2", "temperature", "humidity", "um003"), table.info.properties.map { it.id })
        assertEquals(13, table.rows.size)
        assertEquals(table.rows.sortedWith(compareBy({ it.h3 }, { it.date })), table.rows)
        assertTrue(table.rows.all { it.date != null && it.updatedEpochMs == endOf(it.date) })
        assertEquals(listOf(CAIRO, LAGOS, ACCRA, NAIROBI), table.rows.map { it.h3 }.distinct())

        val cairo = table.rows.single { it.h3 == CAIRO && it.date == oct(5) }
        assertEquals(3, cairo.devices)
        assertEquals(mapOf("pm25" to CellStat(3, 50.0, 35.0, 70.0), "temperature" to CellStat(3, 28.0, 22.0, 33.0)), cairo.stats)
        // One station is a valid public cell.
        assertEquals(1, table.rows.single { it.h3 == ACCRA && it.date == LocalDate(2026, 9, 29) }.devices)
        // A day with stations but nothing measured has no stats, never zeros.
        val empty = table.rows.single { it.h3 == NAIROBI && it.date == oct(8) }
        assertEquals(4, empty.devices)
        assertTrue(empty.stats.isEmpty())
        assertTrue(table.rows.filter { it.h3 == LAGOS }.all { it.mean("pm25") == null && it.mean("humidity") != null })
    }

    @Test
    fun `a day range keeps only rows inside it`() = runTest {
        val table = ParquetFeedReader().readCells(source(), ranges = null, days = oct(2)..oct(8))
        assertEquals(9, table.rows.size)
        assertTrue(table.rows.all { it.date!! in oct(2)..oct(8) })
        assertTrue(table.rows.none { it.h3 == ACCRA })
    }

    @Test
    fun `h3 ranges keep only the cells asked for`() = runTest {
        val table = ParquetFeedReader().readCells(source(), ranges = listOf(CAIRO..CAIRO, NAIROBI..NAIROBI))
        assertEquals(setOf(CAIRO, NAIROBI), table.rows.map { it.h3 }.toSet())
        assertEquals(8, table.rows.size)
    }
}
