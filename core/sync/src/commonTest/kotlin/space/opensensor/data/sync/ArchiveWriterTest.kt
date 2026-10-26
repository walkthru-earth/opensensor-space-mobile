package space.opensensor.data.sync

import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import space.opensensor.archive.PartialWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArchiveWriterTest {
    /** A partial in memory that records its writes and can fail one of them. */
    private class FakePartial(private val failAt: Int? = null) : PartialWriter {
        override val resumeFrom = 0L
        val offsets = mutableListOf<Long>()
        var synced = false
        var closed = false

        override fun writeAt(offset: Long, payload: ByteArray) {
            check(!closed) { "write after close" }
            if (offsets.size == failAt) error("disk full")
            offsets += offset
        }

        override fun length() = offsets.size * CHUNK.toLong()

        override fun sync() {
            synced = true
        }

        override fun close() {
            closed = true
        }
    }

    @Test
    fun aFaultMidWindowDropsTheQueuedChunksAndFailsTheNextWrite() = runTest {
        val partial = FakePartial(failAt = 1)
        val writer = ArchiveWriter(partial, backgroundScope, StandardTestDispatcher(testScheduler), capacity = 4)

        // Four chunks queue before the writer runs, the second fails and the rest are dropped.
        repeat(4) { writer.write(it * CHUNK.toLong(), ByteArray(CHUNK)) }
        runCurrent()

        assertEquals(listOf(0L), partial.offsets)
        assertEquals(CHUNK.toLong(), writer.written)
        assertFailsWith<StorageFault> { writer.write(4 * CHUNK.toLong(), ByteArray(CHUNK)) }
        val fault = assertFailsWith<StorageFault> { writer.finish() }
        assertEquals("disk full", fault.message)
        assertFalse(partial.synced, "a failed partial is never synced")
        assertTrue(partial.closed)
    }

    @Test
    fun abandonWritesWhatIsQueuedAndClosesThePartial() = runTest {
        val partial = FakePartial()
        val writer = ArchiveWriter(partial, backgroundScope, StandardTestDispatcher(testScheduler), capacity = 4)

        repeat(2) { writer.write(it * CHUNK.toLong(), ByteArray(CHUNK)) }
        writer.abandon()
        runCurrent()

        assertEquals(listOf(0L, CHUNK.toLong()), partial.offsets)
        assertTrue(partial.closed, "Stop closes the partial")
    }

    @Test
    fun cancellingTheRunClosesThePartialEvenBeforeTheWriterStarts() = runTest {
        val partial = FakePartial()
        val run = Job()
        val writer = ArchiveWriter(partial, backgroundScope + run, StandardTestDispatcher(testScheduler), capacity = 4)
        repeat(2) { writer.write(it * CHUNK.toLong(), ByteArray(CHUNK)) }

        run.cancelAndJoin()
        runCurrent()

        assertTrue(partial.offsets.isEmpty(), "a cancelled run drops the queue")
        assertTrue(partial.closed, "cancel closes the partial")
    }

    @Test
    fun cancellingWhileTheWriterWaitsClosesThePartial() = runTest {
        val partial = FakePartial()
        val run = Job()
        val scope = backgroundScope + run
        val writer = ArchiveWriter(partial, scope, StandardTestDispatcher(testScheduler), capacity = 4)
        scope.launch { writer.write(0, ByteArray(CHUNK)) }
        runCurrent()
        assertEquals(listOf(0L), partial.offsets)

        run.cancelAndJoin()
        runCurrent()

        assertTrue(partial.closed, "cancel closes the partial")
    }

    private companion object {
        const val CHUNK = 8
    }
}
