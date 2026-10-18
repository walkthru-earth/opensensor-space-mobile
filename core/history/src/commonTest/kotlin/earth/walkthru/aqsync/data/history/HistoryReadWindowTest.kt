package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.archive.ArchiveRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HistoryReadWindowTest {
    private val day = 86_400L
    private val ns = 1_000_000_000L

    private fun record(n: Int, firstNs: Long?, lastNs: Long? = firstNs, indexed: Boolean = true) = ArchiveRecord(
        station = "s", name = "f$n.parquet", bytes = 1, crc32 = 1, sha256 = "s", syncedAtEpochMs = 1, sourceDevice = "fake",
        indexedAtEpochMs = if (indexed) 1 else null, utcRows = if (firstNs == null) 0 else 1, firstUtcNs = firstNs, lastUtcNs = lastNs,
    )

    private val files = listOf(record(1, 100 * day * ns), record(2, 110 * day * ns + 5))

    @Test
    fun aWindowIsClampedToTheArchiveSpanPlusOneDay() {
        assertEquals(99 * day to 111 * day + 1, HistorySeriesReader.readWindow(Long.MIN_VALUE, Long.MAX_VALUE, files))
        assertEquals(105 * day to 106 * day, HistorySeriesReader.readWindow(105 * day, 106 * day, files))
    }

    @Test
    fun aWindowOutsideTheArchiveOrOverAnEmptyOneReadsNothing() {
        assertNull(HistorySeriesReader.readWindow(200 * day, 300 * day, files))
        assertNull(HistorySeriesReader.readWindow(0, 50 * day, files))
        assertNull(HistorySeriesReader.readWindow(0, 200 * day, emptyList()))
        assertNull(HistorySeriesReader.readWindow(0, 200 * day, listOf(record(3, null), record(4, 100 * day * ns, indexed = false))))
        assertNull(HistorySeriesReader.readWindow(106 * day, 105 * day, files))
    }

    @Test
    fun anExtremeTimestampStillGivesNanosecondsThatFit() {
        val extreme = listOf(record(1, Long.MIN_VALUE, Long.MAX_VALUE))
        val window = assertNotNull(HistorySeriesReader.readWindow(Long.MIN_VALUE, Long.MAX_VALUE, extreme))
        assertEquals(-HistorySeriesReader.MAX_WINDOW_S to HistorySeriesReader.MAX_WINDOW_S, window)
        // The reader multiplies by a second and adds a day, neither may wrap.
        assertTrue(window.second * ns + day * ns > 0)
        assertTrue(window.first * ns - day * ns < 0)
    }
}
