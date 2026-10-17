package earth.walkthru.aqsync.publicfeed

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CellMapTest {
    private val pm = FeedProperty("pm25", "PM2.5", "µg/m³", "Air")
    private val temp = FeedProperty("temperature", "Temperature", "°C", "Weather")
    private val rh = FeedProperty("humidity", "Humidity", "%", "Weather")
    private val co2 = FeedProperty("co2", "CO2", "ppm", "Air")
    private val dictionary = listOf(pm, temp, rh, co2)

    private fun row(vararg means: Pair<String, Double>) =
        CellRow(1L, 3, 0L, means.associate { (id, v) -> id to CellStat(1, v, v, v) })

    @Test
    fun `percentile range uses the website ranks`() {
        val values = (1..100).map { it.toDouble() }
        assertEquals(PercentileScale(6.0, 96.0), PercentileScale.of(values))
        assertEquals(PercentileScale(5.0, 5.0), PercentileScale.of(listOf(5.0)))
        assertNull(PercentileScale.of(listOf(Double.NaN)))
    }

    @Test
    fun `scale clamps and maps to the ramp ends`() {
        val scale = PercentileScale(10.0, 20.0)
        assertEquals(SequentialRamp.stops.first(), scale.color(0.0))
        assertEquals(SequentialRamp.stops.last(), scale.color(99.0))
        assertEquals(0.5, scale.fraction(15.0))
        assertEquals("#440154", SequentialRamp.hex(SequentialRamp.stops.first()))
    }

    @Test
    fun `chips rank by visible cells and keep the selection`() {
        val rows = listOf(
            row("temperature" to 20.0, "humidity" to 50.0),
            row("temperature" to 21.0, "pm25" to 9.0),
            row("temperature" to 22.0, "co2" to 400.0, "humidity" to 40.0),
        )
        val ranked = PropertyRanking.rank(rows, dictionary)
        assertEquals(listOf(temp, rh, pm, co2), ranked)
        assertEquals(listOf(temp, rh, pm), PropertyRanking.chips(ranked, selected = null))
        assertEquals(listOf(temp, rh, co2), PropertyRanking.chips(ranked, selected = "co2"))
        assertEquals(listOf(temp, rh, pm), PropertyRanking.chips(ranked, selected = "humidity"))
    }

    @Test
    fun `stale after three hours without a value`() {
        val r = CellRow(1L, 3, updatedEpochMs = 0L, stats = emptyMap())
        assertTrue(CellFreshness.isStale(r, CellFreshness.STALE_AFTER_MS))
        assertTrue(!CellFreshness.isStale(r, CellFreshness.STALE_AFTER_MS - 1))
    }

    @Test
    fun `rings across the antimeridian stay continuous`() {
        val ring = listOf(LatLng(0.0, 179.0), LatLng(1.0, -179.0), LatLng(-1.0, -179.5))
        assertEquals(listOf(179.0, 181.0, 180.5), CellGeoJson.unwrap(ring).map { it.lng })
    }

    @Test
    fun `time zone regions fall back to the continent and the world`() {
        assertEquals(GeoBounds(24.7, 22.0, 36.9, 31.7), TimeZoneRegions.regionFor("Africa/Cairo"))
        assertEquals(GeoBounds(-11.0, 35.0, 32.0, 66.0), TimeZoneRegions.regionFor("Europe/Tallinn"))
        assertEquals(TimeZoneRegions.WORLD, TimeZoneRegions.regionFor("UTC"))
    }

    @Test
    fun `window chip cycles`() {
        assertEquals(FeedWindow.DAY, FeedWindow.LAST_HOUR.next())
        assertEquals(FeedWindow.LAST_HOUR, FeedWindow.WEEK.next())
        assertEquals(
            "cells/window=24h/date=2026-10-08/hour=09/h3_res=7/data.parquet",
            FeedPaths.cells(FeedPartition("2026-10-08", 9), FeedWindow.DAY, 7),
        )
    }

    /** A daily row as [DailyCellFold] makes it, updated at the UTC midnight after its newest day. */
    private fun dailyRow(newest: LocalDate) = CellRow(1L, 1, (newest.toEpochDays() + 1) * 86_400_000L, emptyMap())

    @Test
    fun `a daily row carries the day it measured`() {
        assertEquals(LocalDate(2026, 10, 8), CellFreshness.dataDay(dailyRow(LocalDate(2026, 10, 8))))
    }

    @Test
    fun `a daily row is stale against the partition date and never against the clock`() {
        val asOf = LocalDate(2026, 10, 8)
        assertFalse(CellFreshness.isStaleDaily(dailyRow(asOf), asOf))
        assertFalse(CellFreshness.isStaleDaily(dailyRow(LocalDate(2026, 10, 7)), asOf))
        assertTrue(CellFreshness.isStaleDaily(dailyRow(LocalDate(2026, 10, 6)), asOf))
        // The hourly rule would call the newest day stale 98 hours later.
        assertTrue(CellFreshness.isStale(dailyRow(asOf), dailyRow(asOf).updatedEpochMs + 98 * 3_600_000L))
    }

    @Test
    fun `a property is judged by its own newest day`() {
        // PM last measured on the 3rd, temperature on the 8th (#109).
        val asOf = LocalDate(2026, 10, 8)
        val end = { d: LocalDate -> (d.toEpochDays() + 1) * 86_400_000L }
        val row = dailyRow(asOf).copy(updatedByProperty = mapOf("pm25" to end(LocalDate(2026, 10, 3)), "temperature" to end(asOf)))
        assertTrue(CellFreshness.isStaleDaily(row, asOf, "pm25"))
        assertFalse(CellFreshness.isStaleDaily(row, asOf, "temperature"))
        assertEquals(LocalDate(2026, 10, 3), CellFreshness.dataDay(row, "pm25"))
        assertTrue(CellFreshness.isStale(row, end(asOf), "pm25"))
        assertFalse(CellFreshness.isStale(row, end(asOf), "temperature"))
        // Without a property date the row's newest day decides.
        assertEquals(asOf, CellFreshness.dataDay(row, "humidity"))
    }
}
