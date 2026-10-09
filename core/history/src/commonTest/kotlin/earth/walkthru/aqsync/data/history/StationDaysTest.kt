package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.archive.ArchiveRecord
import kotlin.test.Test
import kotlin.test.assertEquals

class StationDaysTest {
    private val day = StationDays.DAY_NS

    private fun record(station: String, name: String, utcRows: Long? = 1, first: Long? = null, last: Long? = null, estFirst: Long? = null, estLast: Long? = null, purpose: String? = "telemetry", indexed: Boolean = true) =
        ArchiveRecord(station, name, 1, 1, "sha", 1, "d", indexedAtEpochMs = if (indexed) 1 else null, purpose = purpose, utcRows = utcRows, firstUtcNs = first, lastUtcNs = last, estFirstUtcNs = estFirst, estLastUtcNs = estLast)

    @Test
    fun `a file touches every UTC day of its span`() {
        val days = StationDays.of(
            listOf(
                record("a", "one.parquet", first = 20713 * day + 5, last = 20715 * day),
                record("a", "est.parquet", utcRows = 0, estFirst = 20720 * day, estLast = 20720 * day + 1),
                record("b", "early.parquet", first = -1, last = 0),
            ),
        )
        assertEquals(setOf(20713L, 20714L, 20715L, 20720L), days["a"])
        assertEquals(setOf(-1L, 0L), days["b"], "an instant before 1970 belongs to day -1")
    }

    @Test
    fun `unindexed or untimed or other purposes touch no day`() {
        val days = StationDays.of(
            listOf(
                record("a", "x.parquet", first = 0, last = 1, indexed = false),
                record("a", "y.parquet", utcRows = 0),
                record("a", "z.parquet", first = 0, last = 1, purpose = "codec-comparison-duplicate-rows"),
            ),
        )
        assertEquals(emptyMap(), days)
    }
}
