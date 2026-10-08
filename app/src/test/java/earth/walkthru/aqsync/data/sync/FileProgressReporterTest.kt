package earth.walkthru.aqsync.data.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class FileProgressReporterTest {
    @Test
    fun `burst progress is bounded and complete bytes bypass the cadence`() {
        var now = 0L
        val updates = ArrayList<FileProgress>()
        val reporter = FileProgressReporter("file", 1_000, 0, updates::add, monotonicMs = { now })
        reporter.report(0, 0)
        for (offset in 1L..99L) {
            now = offset
            reporter.report(offset, 0)
        }
        assertEquals(listOf(0L), updates.map { it.bytesDone })
        now = 100
        reporter.report(100, 0)
        now = 101
        reporter.report(1_000, 0)
        reporter.report(1_000, 0, force = true)
        assertEquals(listOf(0L, 100L, 1_000L), updates.map { it.bytesDone })
    }

    @Test
    fun `recovery events and final partial bytes bypass the cadence without losing resume accounting`() {
        val updates = ArrayList<FileProgress>()
        val reporter = FileProgressReporter("file", 1_000, 400, updates::add, monotonicMs = { 0 })
        reporter.report(400, 0)
        reporter.report(500, 0)
        reporter.report(500, 1)
        reporter.report(600, 1)
        // Same flush is used on cancellation, transport failure and normal loop exit.
        reporter.report(600, 1, force = true)
        assertEquals(listOf(400L, 500L, 600L), updates.map { it.bytesDone })
        assertEquals(listOf(0, 1, 1), updates.map { it.windowsRecovered })
        assertEquals(listOf(400L, 400L, 400L), updates.map { it.resumedFrom })
    }
}
