package earth.walkthru.opensensor.today

import earth.walkthru.aqsync.archive.ArchiveRecord
import kotlin.test.Test
import kotlin.test.assertEquals

class TodayArchiveTest {
    private fun record(name: String, rows: Long, utcRows: Long? = null, estimated: Boolean = false, indexed: Boolean = true, purpose: String? = null) = ArchiveRecord(
        station = "st-1",
        name = name,
        bytes = 100,
        crc32 = 1,
        sha256 = "00",
        syncedAtEpochMs = 0,
        sourceDevice = "dev",
        indexedAtEpochMs = if (indexed) 1 else null,
        rows = rows,
        purpose = purpose,
        utcRows = utcRows,
        estFirstUtcNs = if (estimated) 1 else null,
        estLastUtcNs = if (estimated) 2 else null,
    )

    @Test
    fun countsIndexedTelemetryAndSplitsEstimatedFromUnplaced() {
        val summary = TodayArchive.of(
            listOf(
                record("a", rows = 10, utcRows = 10),
                record("b", rows = 20, estimated = true),
                record("c", rows = 30),
                record("d", rows = 40, indexed = false),
                record("e", rows = 50, purpose = "benchmark"),
            ),
        )
        assertEquals(TodayArchive(files = 3, estimatedRows = 20, unplacedRows = 30), summary)
    }

    @Test
    fun anEmptyArchiveIsEmpty() {
        assertEquals(TodayArchive(), TodayArchive.of(emptyList()))
    }
}
