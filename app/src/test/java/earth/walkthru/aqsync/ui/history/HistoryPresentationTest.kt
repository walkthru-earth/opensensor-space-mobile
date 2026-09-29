package earth.walkthru.aqsync.ui.history

import earth.walkthru.aqsync.data.local.database.ArchivedFile
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

class HistoryPresentationTest {
    private fun file(station: String, producer: String, dated: Boolean, indexed: Boolean = true): ArchivedFile = ArchivedFile(
        station = station,
        name = if (dated) "station=$producer/year=2026/month=09/day=29/sample.parquet" else "station=$producer/unsynced/boot=one/sample.parquet",
        bytes = 10,
        crc32 = 1,
        sha256 = "hash",
        syncedAtEpochMs = 1,
        sourceDevice = "device",
        indexedAtEpochMs = if (indexed) 1 else null,
        indexError = if (producer != station) "File belongs to station $producer" else null,
        utcRows = if (dated && producer == station) 1 else 0,
        lastUtcNs = if (dated && producer == station) 1_800_000_000_000_000_000 else null,
    )

    @Test
    fun `earlier timed station opens by default but an explicit current selection stays`() {
        val earlier = file("earlier", "earlier", dated = true)
        val current = file("current", "current", dated = false)
        assertEquals("earlier", preferredHistoryStation("current", listOf("earlier", "current"), listOf(earlier, current), null))
        assertEquals("current", preferredHistoryStation("current", listOf("earlier", "current"), listOf(earlier, current), "current"))
    }

    @Test
    fun `retained producer copies are separate from untimed and pending files`() {
        val own = file("current", "current", dated = false)
        val retained = file("current", "earlier", dated = true)
        val pending = file("current", "current", dated = false, indexed = false).copy(name = "station=current/unsynced/boot=one/pending.parquet")
        val counts = countHistoryFiles(listOf(own, retained, pending))
        assertEquals(2, counts.total)
        assertEquals(1, counts.otherStation)
        assertEquals(1, counts.untimed)
        assertEquals(1, counts.pending)
        assertEquals(0, counts.failed)
    }

    @Test
    fun `latest day follows the phone calendar for an earlier station`() {
        val zone = ZoneId.of("Africa/Cairo")
        val utc = java.time.Instant.parse("2026-09-29T13:00:00Z").epochSecond * 1_000_000_000L
        val earlier = file("earlier", "earlier", dated = true).copy(lastUtcNs = utc)
        assertEquals(java.time.LocalDate.of(2026, 9, 30).atStartOfDay(zone).toEpochSecond(), latestHistoryDayEnd(listOf(earlier), zone))
    }
}
