package earth.walkthru.aqsync.data.sync

import earth.walkthru.aqsync.archive.ArchiveKey
import earth.walkthru.aqsync.archive.ArchiveLayout
import earth.walkthru.aqsync.archive.ArchiveStore
import earth.walkthru.aqsync.archive.PartialMeta
import earth.walkthru.aqsync.archive.PartialWriter
import earth.walkthru.aqsync.data.ble.DeviceSession
import earth.walkthru.aqsync.data.ble.openSession
import earth.walkthru.aqsync.data.protocol.Opcode
import earth.walkthru.aqsync.link.LinkEvent
import earth.walkthru.aqsync.storage.FakeStorageBudget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Stop, a late reply after Stop, and a slow disk, over the link a run shares with the next one
 * (issue #55). Stop must not wait for the device, the link settles the abandoned `READ` and the
 * owed `CLOSE`, and a full writer queue holds the next `READ` window.
 */
@OptIn(ExperimentalAtomicApi::class)
class StopAndBackpressureTest {
    private val store = MemoryArchiveStore()
    private val index = MemoryArchiveIndex()

    private fun TestScope.engine(archive: ArchiveStore = store) =
        SyncEngine(archive, index, windowRetryBackoffMs = 5, clock = { 1_000L }, io = StandardTestDispatcher(testScheduler))

    private fun finalBytes(station: String, name: String): ByteArray? = store.files[ArchiveLayout.finalPath(ArchiveKey(station, name))]

    private suspend fun SyncEngine.pull(session: DeviceSession, fake: FakeArchiveDevice, at: Int, progress: (FileProgress) -> Unit = {}): FileOutcome {
        val f = fake.files[at]
        return download(session, fake.station, fake.address, f.name, f.bytes.size.toLong(), fake.maxRead, progress)
    }

    @Test
    fun stopDuringReadReturnsAtOnceThenTheLinkSendsAbortAndCloseAndTheNextViewResumes() = runTest {
        val fake = FakeArchiveDevice(backgroundScope).apply { chunkDelayMs = 1 }
        val first = openSession(fake, backgroundScope)
        val file = fake.files[0]

        val job = launch { engine().pull(first, fake, 0) }
        advanceTimeBy(20)
        runCurrent()
        assertEquals(1, fake.readCount, "the first READ window is streaming")

        val stopAt = currentTime
        job.cancelAndJoin()
        assertTrue(currentTime - stopAt < 100, "Stop took ${currentTime - stopAt} ms of virtual time")

        // The link runs in the background scope, so let virtual time pass for its drain.
        delay(1_000)
        val afterRead = fake.requests.drop(fake.requests.indexOf(Opcode.READ) + 1)
        assertEquals(listOf(Opcode.ABORT, Opcode.CLOSE), afterRead, "requests ${fake.requests}")
        val held = assertNotNull(store.partialLengths(fake.station)[file.name], "the partial stays for resume")
        assertTrue(held > 0, "bytes written before Stop are kept")

        fake.chunkDelayMs = 0
        val second = DeviceSession(first.link, first.transport)
        val progress = ArrayList<FileProgress>()
        assertIs<FileOutcome.Synced>(engine().pull(second, fake, 0) { progress += it })

        assertEquals(held, fake.readOffsets[1], "the second view resumes from the partial, reads ${fake.readOffsets}")
        assertEquals(held, progress.first().resumedFrom)
        assertContentEquals(file.bytes, finalBytes(fake.station, file.name))
        assertEquals(Opcode.CLOSE, fake.requests.last())
    }

    @Test
    fun aLateClosedAfterAStoppedRunIsConsumedAndTheNextRunListsEveryFile() = runTest {
        val events = ArrayList<LinkEvent>()
        val fake = FakeArchiveDevice(backgroundScope).apply {
            chunkDelayMs = 1
            closedDelayMs = 1_500
        }
        val first = DeviceSession.over(fake, backgroundScope, log = events::add).also { it.link.open() }
        val collector = ArchiveCollector(store, index, engine(), FakeStorageBudget(), testTimeSource)

        val runA = launch { collector.collect(first, fake.station, fake.address, fake.maxRead) {} }
        advanceTimeBy(20)
        runCurrent()
        assertEquals(1, fake.readCount, "run A is inside its first READ")
        runA.cancelAndJoin()

        fake.chunkDelayMs = 0
        val second = DeviceSession(first.link, first.transport)
        val startB = currentTime
        val summary = collector.collect(second, fake.station, fake.address, fake.maxRead) {}

        assertEquals(CollectionSummary(3, emptyList()), summary)
        fake.files.forEach { assertContentEquals(it.bytes, finalBytes(fake.station, it.name)) }
        // Run B's LIST queued behind the owed CLOSE, whose CLOSED came 1.5 s later.
        assertTrue(currentTime - startB >= 1_500, "run B waited ${currentTime - startB} ms")
        val tail = fake.requests.drop(fake.requests.indexOf(Opcode.ABORT))
        assertEquals(listOf(Opcode.ABORT, Opcode.CLOSE, Opcode.LIST), tail.take(3), "requests ${fake.requests}")
        assertTrue(events.any { it is LinkEvent.CloseSent }, events.toString())
        assertTrue(events.any { it is LinkEvent.Drained && it.op == Opcode.CLOSE }, events.toString())
        assertTrue(events.none { it is LinkEvent.ForeignDropped }, "no late frame reached run B, $events")
    }

    /** Wraps the partial writer so each `writeAt` waits for a pass on the writer's own thread. */
    private class GatedStore(private val inner: ArchiveStore) : ArchiveStore by inner {
        /** One element lets one write through. Closed, it lets every write through. */
        val passes = Channel<Unit>(Channel.UNLIMITED)
        val entered = AtomicInt(0)

        override suspend fun openPartial(key: ArchiveKey, meta: PartialMeta): PartialWriter {
            val writer = inner.openPartial(key, meta)
            return object : PartialWriter by writer {
                override fun writeAt(offset: Long, payload: ByteArray) {
                    entered.incrementAndFetch()
                    // A disk that does not answer until the test lets a write through.
                    runBlocking { withTimeoutOrNull(10.seconds) { passes.receiveCatching() } } ?: error("the gate never opened")
                    writer.writeAt(offset, payload)
                }
            }
        }
    }

    @Test
    fun aBlockedWriterHoldsTheNextReadAndQueuesAtMostItsCapacity() = runTest {
        val fake = FakeArchiveDevice(backgroundScope)
        val session = openSession(fake, backgroundScope)
        val gated = GatedStore(store)
        // The writer needs a real thread here, since a blocked writeAt blocks the thread it runs on.
        val engine = SyncEngine(gated, index, windowRetryBackoffMs = 5, clock = { 1_000L }, io = Dispatchers.Default)
        val chunk = (fake.mtu - 10).toLong()
        val reported = AtomicLong(-1)

        val download = async { engine.pull(session, fake, 0) { reported.store(it.bytesDone) } }

        // Progress is throttled to one report per 100 ms of real time, so each step waits past it.
        suspend fun settle(entered: Int) = withContext(Dispatchers.Default) {
            withTimeout(5_000) { while (gated.entered.load() < entered) delay(5) }
            delay(250)
        }

        settle(entered = 1)
        assertEquals(1, fake.readCount, "no new READ while the writer is blocked")
        for (step in 2..4) {
            gated.passes.send(Unit)
            settle(entered = step)
            // Chunk `step` is being written. The reader handed over two more and waits on the next.
            assertEquals(step, gated.entered.load())
            assertEquals((step + ArchiveWriter.CAPACITY) * chunk, reported.load(), "chunks handed to the writer at step $step")
            assertEquals(1, fake.readCount, "no new READ while the writer is blocked")
        }

        gated.passes.close()
        assertIs<FileOutcome.Synced>(download.await())
        val file = fake.files[0]
        assertContentEquals(file.bytes, finalBytes(fake.station, file.name))
        val windows = (0 until file.bytes.size step fake.maxRead).map { minOf(fake.maxRead, file.bytes.size - it) }
        assertEquals(windows.sumOf { ((it + chunk - 1) / chunk).toInt() }, gated.entered.load(), "one writeAt per CHUNK")
        assertEquals(2, fake.readCount)
    }
}
