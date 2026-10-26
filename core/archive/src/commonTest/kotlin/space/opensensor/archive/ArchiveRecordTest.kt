package space.opensensor.archive

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArchiveRecordTest {
    private val station = "s"
    private fun record(name: String, utcRows: Long? = null, first: Long? = null, last: Long? = null, estFirst: Long? = null, estLast: Long? = null, purpose: String? = "telemetry", error: String? = null) =
        ArchiveRecord(station, name, 1, 1, "sha", 1, "d", indexedAtEpochMs = 1, indexError = error, purpose = purpose, utcRows = utcRows, firstUtcNs = first, lastUtcNs = last, estFirstUtcNs = estFirst, estLastUtcNs = estLast)

    @Test
    fun `the timeline uses real UTC when the file has it and the estimate otherwise`() {
        val timed = record("t.parquet", utcRows = 3, first = 100, last = 200, estFirst = 900, estLast = 950)
        assertTrue(timed.hasUtc)
        assertFalse(timed.hasEstimatedUtc)
        assertTrue(timed.onTimeline(150, 160))
        assertTrue(timed.onTimeline(200, 300))
        assertFalse(timed.onTimeline(201, 300))
        assertFalse(timed.onTimeline(0, 100))
        assertFalse(timed.onTimeline(900, 1000))
        val estimated = record("e.parquet", utcRows = 0, estFirst = 900, estLast = 950)
        assertTrue(estimated.hasEstimatedUtc)
        assertEquals(900L, estimated.timelineFirstNs)
        assertTrue(estimated.onTimeline(940, 1000))
    }

    @Test
    fun `unplaced files and other purposes and failed indexing stay off the timeline`() {
        assertFalse(record("u.parquet").onTimeline(Long.MIN_VALUE + 1, Long.MAX_VALUE))
        assertFalse(record("b.parquet", 3, 100, 200, purpose = "codec-comparison-duplicate-rows").onTimeline(0, 1000))
        assertFalse(record("x.parquet", 3, 100, 200, error = "bad").onTimeline(0, 1000))
        // A reopened record keeps its old span but waits for its index, so it is off the timeline too.
        val reopened = record("r.parquet", 3, 100, 200).copy(indexedAtEpochMs = null)
        assertFalse(reopened.isIndexed)
        assertTrue(reopened.awaitsIndex)
        assertFalse(reopened.onTimeline(0, 1000))
        assertTrue(record("n.parquet", 3, 100, 200, purpose = null).onTimeline(0, 1000))
    }

    @Test
    fun `a record with no end or no start is off every timeline even the widest`() {
        val noEnd = record("n.parquet", utcRows = 3, first = 100)
        assertTrue(noEnd.hasHistoryTime)
        assertFalse(noEnd.onTimeline(Long.MIN_VALUE, Long.MAX_VALUE))
        assertFalse(noEnd.onTimeline(Long.MIN_VALUE, 101))
        val noStart = record("s.parquet", utcRows = 3, last = 200)
        assertFalse(noStart.onTimeline(Long.MIN_VALUE, Long.MAX_VALUE))
        val estimatedNoEnd = record("e.parquet", utcRows = 0, estFirst = 100).copy(estLastUtcNs = null)
        assertFalse(estimatedNoEnd.onTimeline(Long.MIN_VALUE, Long.MAX_VALUE))
    }

    @Test
    fun `real spans at the edges of Long stay on the timeline`() {
        val lowest = record("lo.parquet", utcRows = 1, first = Long.MIN_VALUE, last = Long.MIN_VALUE)
        assertTrue(lowest.onTimeline(Long.MIN_VALUE, Long.MIN_VALUE + 1))
        assertFalse(lowest.onTimeline(Long.MIN_VALUE + 1, Long.MAX_VALUE))
        assertFalse(lowest.onTimeline(Long.MIN_VALUE, Long.MIN_VALUE))
        val highest = record("hi.parquet", utcRows = 1, first = Long.MAX_VALUE - 1, last = Long.MAX_VALUE)
        assertTrue(highest.onTimeline(Long.MAX_VALUE - 1, Long.MAX_VALUE))
        assertTrue(highest.onTimeline(Long.MIN_VALUE, Long.MAX_VALUE))
    }

    @Test
    fun `latest timed ends keep no end apart from a real end at the low edge`() {
        val noEnd = record("n.parquet", utcRows = 3, first = 100)
        val lowEnd = record("lo.parquet", utcRows = 1, first = Long.MIN_VALUE, last = Long.MIN_VALUE)
        val end = record("e.parquet", utcRows = 1, first = 1, last = 5)
        assertEquals(mapOf<String, Long?>(station to null), ArchiveIndex.latestTimedEnds(listOf(noEnd)))
        assertEquals(mapOf<String, Long?>(station to Long.MIN_VALUE), ArchiveIndex.latestTimedEnds(listOf(noEnd, lowEnd)))
        assertEquals(mapOf<String, Long?>(station to Long.MIN_VALUE), ArchiveIndex.latestTimedEnds(listOf(lowEnd, noEnd)))
        assertEquals(mapOf<String, Long?>(station to 5L), ArchiveIndex.latestTimedEnds(listOf(noEnd, end, lowEnd)))
        assertEquals(mapOf<String, Long?>(station to 5L), ArchiveIndex.latestTimedEnds(listOf(end, noEnd)))
        assertEquals(emptyMap<String, Long?>(), ArchiveIndex.latestTimedEnds(listOf(record("u.parquet"))))
    }

    @Test
    fun `a record matches the listing only with the same size and CRC`() {
        val r = record("t.parquet")
        assertTrue(r.matches(1, 1))
        assertFalse(r.matches(2, 1))
        assertFalse(r.matches(1, 2))
    }

    @Test
    fun `the index timeline is filtered and ordered by first time`() = runTest {
        val index = InMemoryArchiveIndex()
        index.put(record("late.parquet", 1, 500, 600))
        index.put(record("early.parquet", 0, estFirst = 100, estLast = 150))
        index.put(record("off.parquet", 1, 5_000, 6_000))
        assertEquals(listOf("early.parquet", "late.parquet"), index.timeline(station, 0, 1_000).map { it.name })
    }
}
