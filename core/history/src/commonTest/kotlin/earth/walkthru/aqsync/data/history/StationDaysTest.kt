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

    @Test
    fun `sources by day match the time query predicate for every day`() {
        val files = listOf(
            record("a", "late.parquet", first = 20714 * day + 10, last = 20714 * day + 20),
            record("a", "span.parquet", first = 20713 * day + 5, last = 20715 * day),
            record("a", "est.parquet", utcRows = 0, estFirst = 20714 * day - 1, estLast = 20714 * day),
            record("a", "edge.parquet", first = 20716 * day - 1, last = 20716 * day - 1),
            record("a", "untimed.parquet", utcRows = 0),
            record("a", "broken.parquet", first = 20714 * day, last = 20714 * day).copy(indexError = "bad"),
            record("a", "other.parquet", first = 20714 * day, last = 20714 * day, purpose = "codec-comparison-duplicate-rows"),
            record("a", "backwards.parquet", first = 20715 * day + 9, last = 20714 * day),
        )
        val byDay = StationDays.sourcesByDay(files) { it }
        for (d in 20712L..20717L) {
            val expected = files.filter { it.onTimeline(d * day, (d + 1) * day) }.sortedWith(ArchiveRecord.TIMELINE_ORDER)
            assertEquals(expected.map { it.name }, byDay[d].orEmpty().map { it.name }, "day $d")
        }
        assertEquals(listOf("span.parquet", "est.parquet", "late.parquet"), byDay[20714]!!.map { it.name })
    }
}

