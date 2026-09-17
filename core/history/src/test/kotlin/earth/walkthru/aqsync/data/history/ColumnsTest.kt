package earth.walkthru.aqsync.data.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColumnsTest {

    private fun series(points: List<HistoryPoint>, fromS: Long, toS: Long) =
        HistorySeries(Metrics.PM25, HistoryRange.DAY, fromS, toS, points, validRows = 0, expectedRows = 0, mean = null, max = null, binned = true)

    @Test
    fun `a day is 24 hourly columns, a week 168, a month 30, a year 53 weeks`() {
        // 365 days aligned to weeks span 53 week slots.
        for ((range, n) in listOf(HistoryRange.DAY to 24, HistoryRange.WEEK to 168, HistoryRange.MONTH to 30, HistoryRange.YEAR to 53)) {
            val s = HistorySeries(Metrics.PM25, range, 0, range.seconds, emptyList(), validRows = 0, expectedRows = 0, mean = null, max = null, binned = false)
            assertEquals(range.name, n, Columns.rebin(s, Columns.slotSeconds(range)).size)
        }
    }

    @Test
    fun `rebin weights by covered time and keeps gaps as slots`() {
        // Three 1-minute points in the first quarter hour: full coverage 10, full coverage 30, half coverage 100.
        val pts = listOf(
            HistoryPoint(0, 60, 10f, 10f, 12f, 1f),
            HistoryPoint(60, 120, 30f, 30f, 30f, 1f),
            HistoryPoint(120, 180, 100f, 100f, 100f, 0.5f),
            HistoryPoint(1800, 1860, 5f, 5f, 5f, 1f, estimated = true),
        )
        val cols = Columns.rebin(series(pts, 0, 3600), 900)
        assertEquals(4, cols.size)
        // (10·60 + 30·60 + 100·30) / 150 = 36
        assertEquals(36f, cols[0].mean!!, 1e-4f)
        assertEquals(100f, cols[0].max)
        assertEquals(150f / 900f, cols[0].coverage, 1e-6f)
        assertEquals(0f, cols[0].estimatedShare)
        assertNull(cols[1].mean) // gap
        assertEquals(0f, cols[1].coverage)
        assertTrue(cols[2].estimated) // the estimated point is its only content
        assertEquals(5f, cols[2].mean)
        assertNull(cols[3].mean)
    }

    @Test
    fun `axis follows the 95th percentile, not the spike`() {
        val cols = (0 until 96).map { i -> HistoryColumn(i * 900L, (i + 1) * 900L, if (i == 40) 379f else 12f + (i % 5), 12f, 1f, 0f) }
        val top = Columns.axisMax(cols, 60f)
        assertTrue("axis $top should stay near the data, not 600", top in 60f..80f)
        assertEquals(60f, Columns.axisMax(emptyList(), 60f))
        // A generally high day is not clipped.
        val high = (0 until 96).map { i -> HistoryColumn(i * 900L, (i + 1) * 900L, 120f, 130f, 1f, 0f) }
        assertTrue(Columns.axisMax(high, 60f) >= 144f)
    }

    @Test
    fun `nice steps`() {
        assertEquals(10f, Columns.niceStep(40f))
        assertEquals(20f, Columns.niceStep(80f))
        assertEquals(50f, Columns.niceStep(160f))
        assertEquals(1f, Columns.niceStep(0f))
    }
}
