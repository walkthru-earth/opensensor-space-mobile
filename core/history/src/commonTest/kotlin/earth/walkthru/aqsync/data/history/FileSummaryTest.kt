package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.data.history.parquet.ParquetFile
import kotlin.test.Test

class FileSummaryTest {

    private fun bytes(path: String) = fixtureBytes("parquet/$path")

    @Test
    fun `a timed board file is one column with the PyArrow mean and its UTC span`() {
        val s = FileSummary.of("data_0930.parquet", ParquetFile.wrap(bytes("device/v3-board-2rg-105rows.parquet")))
        assertEquals(105, s.rows)
        assertEquals(105, s.timedRows)
        assertEquals(0, s.estimatedRows)
        assertFalse(s.untimed)
        assertEquals(1_789_638_155L, s.firstS) // 2026-09-17T09:42:35Z
        assertEquals(1_789_639_195L, s.lastS) // 09:59:55Z
        assertEquals(27.9333, s.agg(Metrics.PM25)!!.mean.toDouble(), 0.001)
        assertEquals(41.4009, s.agg(Metrics.BOARD_TEMP)!!.mean.toDouble(), 0.001)
        assertNull(s.agg(Metrics.HUMIDITY)) // column absent on this board: no aggregate, never zero
        val c = s.column(7, Metrics.PM25)
        assertEquals(7L, c.startS)
        assertEquals(8L, c.endS)
        assertEquals(27.9333f, c.mean!!, 0.001f)
        assertEquals(1f, c.coverage, 1e-6f) // every row passed the PMS gate
        assertFalse(c.estimated)
    }

    @Test
    fun `an unsynced file without an anchor still has readings but no place on the timeline`() {
        val s = FileSummary.of("data_unsynced.parquet", ParquetFile.wrap(bytes("device/v2-none-90rows-unsynced.parquet")))
        assertEquals(90, s.rows)
        assertEquals(0, s.timedRows)
        assertEquals(0, s.estimatedRows)
        assertTrue(s.untimed)
        assertNull(s.firstS)
        assertNotNull(s.agg(Metrics.BOARD_TEMP))
        val c = s.column(0, Metrics.BOARD_TEMP)
        assertNotNull(c.mean)
        assertFalse(c.estimated)
    }
}
