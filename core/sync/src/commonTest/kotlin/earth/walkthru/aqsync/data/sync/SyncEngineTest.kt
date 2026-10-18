package earth.walkthru.aqsync.data.sync

import earth.walkthru.aqsync.archive.ArchiveKey
import earth.walkthru.aqsync.archive.ArchiveLayout
import earth.walkthru.aqsync.archive.PartialMeta
import earth.walkthru.aqsync.data.ble.DeviceSession
import earth.walkthru.aqsync.data.ble.WindowLostException
import earth.walkthru.aqsync.data.protocol.FrameType
import earth.walkthru.aqsync.data.protocol.Opcode
import earth.walkthru.aqsync.link.LinkEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The per-file transfer both phones run (docs/ble-sync-protocol.md), over in-memory ports. The lossy
 * cases reproduce what a phone saw on 2026-09-17 (runs of leading CHUNKs missing from a window). The
 * archive cases pin the immutable-history rules. Android's adapters run the same engine in `:app`.
 */
class SyncEngineTest {
    private val store = MemoryArchiveStore()
    private val index = MemoryArchiveIndex()
    private val coordinator = DeviceCoordinator()

    /** What the link reported. A frame it dropped as foreign is what the old stale-frame log caught. */
    private val linkEvents = ArrayList<LinkEvent>()

    /** One engine per call shares [store], [index] and [coordinator], and writes on the test's virtual clock. */
    private fun TestScope.engine() =
        SyncEngine(store, index, windowRetryBackoffMs = 5, clock = { 1_000L }, coordinator = coordinator, io = StandardTestDispatcher(testScheduler))

    private suspend fun device(scope: CoroutineScope, address: String = "fake-0"): Pair<FakeArchiveDevice, DeviceSession> {
        val fake = FakeArchiveDevice(scope, address = address)
        val session = DeviceSession.over(fake, scope, log = linkEvents::add).also { it.link.open() }
        return fake to session
    }

    private fun foreignDropped() = linkEvents.filterIsInstance<LinkEvent.ForeignDropped>()

    private suspend fun SyncEngine.pull(
        device: Pair<FakeArchiveDevice, DeviceSession>,
        index: Int,
        station: String = device.first.station,
        progress: (FileProgress) -> Unit = {},
    ): FileOutcome {
        val f = device.first.files[index]
        return download(device.second, station, device.first.address, f.name, f.bytes.size.toLong(), device.first.maxRead, progress)
    }

    private fun finalBytes(station: String, name: String): ByteArray? = store.files[ArchiveLayout.finalPath(ArchiveKey(station, name))]

    private fun hasPartials(): Boolean = store.files.keys.any { it.startsWith(ArchiveLayout.PARTIAL + "/") }

    @Test
    fun settledCallbacksReportNewAndReusedArchivesOnceBeforeEachClose() = runTest {
        val d = device(backgroundScope)
        val fake = d.first
        val file = fake.files.first()
        val outcomes = ArrayList<FileOutcome>()
        val closesAtSettlement = ArrayList<Int>()
        val settle: (FileOutcome) -> Unit = { outcome ->
            outcomes += outcome
            closesAtSettlement += fake.requests.count { it == Opcode.CLOSE }
            assertContentEquals(file.bytes, finalBytes(fake.station, file.name))
            assertEquals(file.name, index.records.single().name, "verified bytes and their receipt exist before callback")
        }

        val first = engine().download(d.second, fake.station, fake.address, file.name, file.bytes.size.toLong(), fake.maxRead, onSettled = settle) {}
        val second = engine().download(d.second, fake.station, fake.address, file.name, file.bytes.size.toLong(), fake.maxRead, onSettled = settle) {}

        assertEquals(listOf(first, second), outcomes)
        assertEquals(listOf(0, 1), closesAtSettlement)
        assertFalse(assertIs<FileOutcome.Synced>(first).alreadyArchived)
        assertTrue(assertIs<FileOutcome.Synced>(second).alreadyArchived)
        assertEquals(1, index.puts, "reuse preserves the existing receipt and provenance")
        assertEquals(0, coordinator.activeWriters())
    }

    @Test
    fun aFailedReadStillClosesThePartialWriter() = runTest {
        val d = device(backgroundScope)
        // Every window loses every CHUNK, so the engine gives up and returns from inside the writer block.
        d.first.dropLeadingChunks = { 99 }

        val outcome = engine().pull(d, 0)

        assertIs<FileOutcome.Failed>(outcome)
        assertEquals(0, store.openWriters, "the partial writer is closed on an early return")
        assertTrue(hasPartials(), "the partial is kept for resume")
    }

    @Test
    fun lostWindowsAreReReadFromThePhoneOffsetAndCounted() = runTest {
        val d = device(backgroundScope)
        // File 0 is 24 000 bytes in 507-byte chunks. READ 1 (16384 at 0) loses 3 CHUNKs, so the window
        // halves to 8112 and READ 2 is intact. READ 3 (8112 at 8112) loses every CHUNK and READ 4 (4056)
        // loses 2, so the window sits at its 2028-byte floor. READ 5 and the 7 reads after it are intact.
        d.first.dropLeadingChunks = { k -> when (k) { 1 -> 3; 3 -> 99; 4 -> 2; else -> 0 } }
        val progress = ArrayList<FileProgress>()

        val outcome = engine().pull(d, 0) { progress += it }

        val synced = assertIs<FileOutcome.Synced>(outcome)
        val file = d.first.files[0]
        assertEquals(file.bytes.size.toLong(), synced.bytes)
        assertEquals(12, d.first.readCount)
        assertEquals(listOf(16384L, 8112L, 8112L, 4056L) + List(8) { 2028L }, d.first.readLengths)
        assertEquals(2, progress.last().windowsRecovered)
        assertTrue(progress.zipWithNext().all { (a, b) -> b.bytesDone >= a.bytesDone }, "progress never goes backwards")
        assertContentEquals(file.bytes, finalBytes(d.first.station, file.name))
        assertEquals(synced.sha256, index.records.single().sha256)
        assertEquals("fake-0", index.records.single().sourceDevice)
        assertTrue(foreignDropped().isEmpty(), foreignDropped().toString())
        assertFalse(hasPartials())
    }

    @Test
    fun aWindowThatKeepsFailingFailsOnlyThatFileAndTheNextFileSyncs() = runTest {
        val d = device(backgroundScope)
        // 1 initial attempt and 5 retries, all lossy, so the file is given up. READ 7 and later are intact.
        d.first.dropLeadingChunks = { k -> if (k <= 6) 5 else 0 }

        val first = assertIs<FileOutcome.Failed>(engine().pull(d, 0))
        assertTrue(first.message.contains("lost 6×"), first.message)
        assertEquals(6, d.first.readCount)
        assertTrue(index.records.isEmpty())

        assertIs<FileOutcome.Synced>(engine().pull(d, 1))
        assertEquals(d.first.files[1].name, index.records.single().name)
        assertContentEquals(d.first.files[1].bytes, finalBytes(d.first.station, d.first.files[1].name))
    }

    @Test
    fun aWeakLinkShrinksTheWindowUntilReadsLandAndTheNextFileKeepsTheSmallWindow() = runTest {
        val d = device(backgroundScope)
        // A link that loses the head of every window longer than 8 frames. A fixed 16 KiB window
        // would lose all 6 attempts and fail the file. The adaptive one halves to 8 frames and lands.
        val fake = d.first
        fake.dropLeadingChunks = { if (fake.readLengths.last() > 8 * 507) 1 else 0 }

        assertIs<FileOutcome.Synced>(engine().pull(d, 0))
        assertEquals(listOf(16384L, 8112L, 4056L), fake.readLengths.take(3))
        assertContentEquals(fake.files[0].bytes, finalBytes(fake.station, fake.files[0].name))

        val before = fake.readLengths.size
        assertIs<FileOutcome.Synced>(engine().pull(d, 1))
        assertTrue(fake.readLengths.drop(before).all { it <= 6084L }, "the link remembers it is weak: ${fake.readLengths}")
        assertContentEquals(fake.files[1].bytes, finalBytes(fake.station, fake.files[1].name))
    }

    @Test
    fun sessionThrowsWindowLostOnlyAfterTheReadEndWasConsumed() = runTest {
        val (fake, session) = device(backgroundScope)
        val opened = session.open(fake.files[2].name)
        fake.dropLeadingChunks = { k -> if (k == 1) 4 else 0 }
        var delivered = 0L
        val e = assertFailsWith<WindowLostException> { session.read(opened.handle, 0, 4096) { delivered += it.payload.size } }
        assertEquals(0L, e.receivedUpTo)
        assertEquals(0L, delivered)
        assertTrue(e.message!!.startsWith("gap in stream: expected offset 0, got CHUNK at 2028"), e.message)
        // The very next READ gets its own frames, not the tail of the lost window.
        val end = session.read(opened.handle, 0, 4096) { delivered += it.payload.size }
        assertEquals(4096L, end.nextOffset)
        assertEquals(4096L, delivered)
        assertTrue(foreignDropped().isEmpty(), foreignDropped().toString())
        assertEquals(opened.handle, session.close(opened.handle).handle)
    }

    @Test
    fun aClosedArrivingAfterTheCallerGaveUpIsDrainedByTheLinkNotTakenForTheListReply() = runTest {
        val (fake, session) = device(backgroundScope)
        val opened = session.open(fake.files[2].name)
        fake.closedDelayMs = 1_500
        assertNull(withTimeoutOrNull(300) { session.close(opened.handle) }, "close must time out for this test to mean anything")

        val (files, end) = session.list()
        assertEquals(fake.files.size, files.size)
        assertEquals(fake.files.size.toLong(), end.count)
        // The abandoned CLOSE was handed to the link, which sent ABORT for it. This fake answers
        // ABORT at once, ahead of the delayed CLOSED, so the drain ends on ABORTED and the late
        // CLOSED reaches the router with no owner. It is dropped, never handed to a request.
        assertTrue(linkEvents.any { it is LinkEvent.Abandoned && it.op == Opcode.CLOSE }, linkEvents.toString())
        assertTrue(linkEvents.any { it is LinkEvent.AbortSent && it.op == Opcode.CLOSE }, linkEvents.toString())
        delay(2_000)
        assertTrue(linkEvents.any { it is LinkEvent.ForeignDropped && it.type == FrameType.CLOSED }, linkEvents.toString())
        assertEquals(fake.files.size, session.list().first.size, "the link is still in step")
    }

    @Test
    fun differentStationsTransferTheSameNameAtTheSameTime() = runTest {
        val a = device(backgroundScope, "fake-a").also { it.first.chunkDelayMs = 1 }
        val b = device(backgroundScope, "fake-b").also { it.first.chunkDelayMs = 1 }
        val events = ArrayList<String>()
        val first = async { engine().pull(a, 2, station = "station-a") { if (it.bytesDone == 0L) events += "a started" } }
        val second = async { engine().pull(b, 2, station = "station-b") { if (it.bytesDone == 0L) events += "b started" } }
        val outcomes = listOf(first.await(), second.await())
        assertTrue(outcomes.all { it is FileOutcome.Synced && !it.alreadyArchived }, outcomes.toString())
        // Both started before either finished: distinct identities never wait on each other.
        assertEquals(listOf("a started", "b started"), events.take(2))
        assertContentEquals(a.first.files[2].bytes, finalBytes("station-a", a.first.files[2].name))
        assertContentEquals(b.first.files[2].bytes, finalBytes("station-b", b.first.files[2].name))
        assertEquals(2, index.records.size)
    }

    @Test
    fun sameStationAndNameHasOneWriterAndTheWaiterRechecksVerifiedBytes() = runTest {
        val a = device(backgroundScope, "fake-a").also { it.first.chunkDelayMs = 1 }
        val b = device(backgroundScope, "fake-b").also { it.first.chunkDelayMs = 1 }
        val first = async { engine().pull(a, 0, station = "station-a") }
        val second = async { engine().pull(b, 0, station = "station-a") }
        val outcomes = listOf(first.await(), second.await())
        assertTrue(outcomes.all { it is FileOutcome.Synced }, outcomes.toString())
        assertEquals(1, outcomes.count { (it as FileOutcome.Synced).alreadyArchived })
        val windows = (a.first.files[0].bytes.size + a.first.maxRead - 1) / a.first.maxRead
        assertEquals(windows, a.first.readCount + b.first.readCount)
        assertEquals(1, index.records.size)
        assertEquals(1, index.puts)
        assertFalse(hasPartials())
        assertEquals(0, coordinator.activeWriters())
    }

    @Test
    fun changedProducerBytesNeverReplaceAnExistingVerifiedArchive() = runTest {
        val a = device(backgroundScope, "fake-a")
        assertIs<FileOutcome.Synced>(engine().pull(a, 0))
        val original = a.first.files[0]
        val b = device(backgroundScope, "fake-b")
        val changed = original.bytes.copyOf().also { it[100] = (it[100].toInt() xor 1).toByte() }
        b.first.files[0] = FakeArchiveDevice.FakeFile(original.name, changed)
        assertIs<FileOutcome.Mismatch>(engine().pull(b, 0))
        assertEquals(0, b.first.readCount)
        assertContentEquals(original.bytes, finalBytes(a.first.station, original.name))
        assertEquals(1, index.puts)
    }

    @Test
    fun aCorruptedLocalArchiveIsPreservedAndSurfaced() = runTest {
        val a = device(backgroundScope)
        assertIs<FileOutcome.Synced>(engine().pull(a, 0))
        val file = a.first.files[0]
        val path = ArchiveLayout.finalPath(ArchiveKey(a.first.station, file.name))
        val corrupted = file.bytes.copyOf().also { it[100] = (it[100].toInt() xor 1).toByte() }
        store.files[path] = corrupted
        val b = device(backgroundScope, "fake-b")
        assertIs<FileOutcome.Mismatch>(engine().pull(b, 0))
        assertContentEquals(corrupted, store.files[path])
        assertEquals(0, b.first.readCount)
    }

    @Test
    fun aCorruptedResumablePrefixIsRetriedFromZeroInTheSameDownload() = runTest {
        val d = device(backgroundScope)
        val file = d.first.files[0]
        val key = ArchiveKey(d.first.station, file.name)
        val prefix = file.bytes.size / 2
        store.openPartial(key, PartialMeta(file.bytes.size.toLong(), file.crc32)).use { w ->
            w.writeAt(0, file.bytes.copyOfRange(0, prefix).also { it[100] = (it[100].toInt() xor 1).toByte() })
        }

        assertIs<FileOutcome.Synced>(engine().pull(d, 0))

        assertContentEquals(file.bytes, finalBytes(key.station, key.name))
        assertFalse(hasPartials())
        val fullWindows = (file.bytes.size + d.first.maxRead - 1) / d.first.maxRead
        assertTrue(d.first.readCount > fullWindows, "a clean re-read must follow the corrupt resume")
        assertEquals(1, index.puts)
    }

    @Test
    fun aCleanPartialResumesFromItsLength() = runTest {
        val d = device(backgroundScope)
        val file = d.first.files[0]
        val key = ArchiveKey(d.first.station, file.name)
        store.openPartial(key, PartialMeta(file.bytes.size.toLong(), file.crc32)).use { w -> w.writeAt(0, file.bytes.copyOfRange(0, 16_384)) }
        val progress = ArrayList<FileProgress>()

        assertIs<FileOutcome.Synced>(engine().pull(d, 0) { progress += it })

        assertEquals(1, d.first.readCount)
        assertEquals(16_384L, progress.first().resumedFrom)
        assertContentEquals(file.bytes, finalBytes(key.station, key.name))
    }

    @Test
    fun aRecordedHashConflictPreservesBytesAndProvenance() = runTest {
        val a = device(backgroundScope)
        assertIs<FileOutcome.Synced>(engine().pull(a, 0))
        val original = index.records.single()
        index.put(original.copy(sha256 = "invalid-recorded-fingerprint"))
        val b = device(backgroundScope, "fake-b")
        assertIs<FileOutcome.Mismatch>(engine().pull(b, 0))
        assertEquals(0, b.first.readCount)
        assertEquals(original.sourceDevice, index.records.single().sourceDevice)
        assertContentEquals(a.first.files[0].bytes, finalBytes(original.station, original.name))
    }

    @Test
    fun aMissingRecordForAVerifiedCopyIsWrittenOnce() = runTest {
        val a = device(backgroundScope)
        assertIs<FileOutcome.Synced>(engine().pull(a, 0))
        index.remove(index.records.single().key)
        val b = device(backgroundScope, "fake-b")
        val again = assertIs<FileOutcome.Synced>(engine().pull(b, 0))
        assertTrue(again.alreadyArchived)
        assertEquals("fake-b", index.records.single().sourceDevice)
        assertEquals(0, b.first.readCount)
    }

    @Test
    fun aFinalThatAppearsBeforeTheCommitIsAdoptedNotReplaced() = runTest {
        val a = device(backgroundScope)
        val file = a.first.files[0]
        val key = ArchiveKey(a.first.station, file.name)
        // Another writer committed the same verified bytes after the planner looked, and crashed before indexing.
        store.beforeCommit = { store.files[ArchiveLayout.finalPath(it)] = file.bytes.copyOf() }

        val outcome = assertIs<FileOutcome.Synced>(engine().pull(a, 0))

        assertTrue(outcome.alreadyArchived)
        assertContentEquals(file.bytes, finalBytes(key.station, key.name))
        assertEquals(key, index.records.single().key)
        assertFalse(hasPartials())
    }

    @Test
    fun aConflictingFinalThatAppearsBeforeTheCommitIsKept() = runTest {
        val a = device(backgroundScope)
        val file = a.first.files[0]
        val other = file.bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        store.beforeCommit = { store.files[ArchiveLayout.finalPath(it)] = other }

        assertIs<FileOutcome.Mismatch>(engine().pull(a, 0))

        assertContentEquals(other, finalBytes(a.first.station, file.name))
        assertTrue(index.records.isEmpty())
    }

    @Test
    fun aRecordWithoutAFingerprintIsFilledNotRefused() = runTest {
        val a = device(backgroundScope)
        assertIs<FileOutcome.Synced>(engine().pull(a, 0))
        val synced = index.records.single()
        index.put(synced.copy(sha256 = ""))
        val again = assertIs<FileOutcome.Synced>(engine().pull(device(backgroundScope, "fake-b"), 0))
        assertTrue(again.alreadyArchived)
        assertEquals(synced.sha256, index.records.single().sha256)
        assertEquals("fake-0", index.records.single().sourceDevice)
    }

    @Test
    fun theIndexerFillsTheRecordInTheSameWrite() = runTest {
        val indexed = SyncEngine(
            store,
            index,
            indexer = { it.copy(rows = 90, indexedAtEpochMs = 2) },
            windowRetryBackoffMs = 5,
            io = StandardTestDispatcher(testScheduler),
        )
        assertIs<FileOutcome.Synced>(indexed.pull(device(backgroundScope), 2))
        assertEquals(1, index.puts)
        assertEquals(90L, index.records.single().rows)
    }

    @Test
    fun theFooterIsIndexedOutsideTheStationGateOnCommitAndAdopt() = runTest {
        // Gate holders seen by each footer read. The gate must cover only the short row write.
        val heldWhileIndexing = ArrayList<Int>()
        val engine = SyncEngine(
            store,
            index,
            indexer = { heldWhileIndexing += coordinator.activeStations(); it.copy(rows = 90, indexedAtEpochMs = 2) },
            windowRetryBackoffMs = 5,
            clock = { 1_000L },
            coordinator = coordinator,
            io = StandardTestDispatcher(testScheduler),
        )
        assertIs<FileOutcome.Synced>(engine.pull(device(backgroundScope), 0))
        // The record is gone, so the next pull adopts the verified copy and indexes its footer again.
        index.remove(index.records.single().key)
        val adopted = assertIs<FileOutcome.Synced>(engine.pull(device(backgroundScope, "fake-b"), 0))

        assertTrue(adopted.alreadyArchived)
        assertEquals(listOf(0, 0), heldWhileIndexing)
        assertEquals(90L, index.records.single().rows)
    }

    @Test
    fun anUnsafeNameIsRefusedWithoutOpening() = runTest {
        val d = device(backgroundScope)
        val outcome = engine().download(d.second, d.first.station, "fake", "../escape.parquet", 12, 4096) {}
        assertIs<FileOutcome.Failed>(outcome)
        assertEquals(0, d.first.readCount)
    }

    @Test
    fun aCancelledWaiterDoesNotDropAWriterLeaseOrBlockLaterTransfers() = runTest {
        val d = device(backgroundScope)
        val key = ArchiveKey(d.first.station, d.first.files[0].name)
        val holding = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val owner = launch { coordinator.withWriter(key) { holding.complete(Unit); release.await() } }
        holding.await()
        val waiter = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.withWriter(key) { fail("cancelled waiter ran") } }
        waiter.cancelAndJoin()
        release.complete(Unit)
        owner.join()
        assertEquals(0, coordinator.activeWriters())
        assertIs<FileOutcome.Synced>(engine().pull(d, 0))
    }
}
