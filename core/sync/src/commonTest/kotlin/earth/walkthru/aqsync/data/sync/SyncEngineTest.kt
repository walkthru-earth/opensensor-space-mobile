package earth.walkthru.aqsync.data.sync

import earth.walkthru.aqsync.archive.ArchiveKey
import earth.walkthru.aqsync.archive.ArchiveLayout
import earth.walkthru.aqsync.archive.PartialMeta
import earth.walkthru.aqsync.data.ble.DeviceSession
import earth.walkthru.aqsync.data.ble.WindowLostException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
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
    private val leases = WriterLeases()
    private val engine = SyncEngine(store, index, windowRetryBackoffMs = 5, clock = { 1_000L }, leases = leases)
    private val staleLog = ArrayList<String>()

    private fun device(scope: CoroutineScope, address: String = "fake-0"): Pair<FakeArchiveDevice, DeviceSession> {
        val fake = FakeArchiveDevice(scope, address = address)
        return fake to DeviceSession(fake).also { it.staleFrameLogger = staleLog::add }
    }

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
    fun lostWindowsAreReReadFromThePhoneOffsetAndCounted() = runTest {
        val d = device(backgroundScope)
        // File 0 is 24 000 bytes, so two 16 KiB windows of 507-byte chunks.
        // READ 1 (window at 0) loses 3 CHUNKs. READ 2 (retry) is intact.
        // READ 3 (window at 16384) loses every CHUNK. READ 4 loses 2 again. READ 5 is intact.
        d.first.dropLeadingChunks = { k -> when (k) { 1 -> 3; 3 -> 99; 4 -> 2; else -> 0 } }
        val progress = ArrayList<FileProgress>()

        val outcome = engine.pull(d, 0) { progress += it }

        val synced = assertIs<FileOutcome.Synced>(outcome)
        val file = d.first.files[0]
        assertEquals(file.bytes.size.toLong(), synced.bytes)
        assertEquals(5, d.first.readCount)
        assertEquals(2, progress.last().windowsRecovered)
        assertTrue(progress.zipWithNext().all { (a, b) -> b.bytesDone >= a.bytesDone }, "progress never goes backwards")
        assertContentEquals(file.bytes, finalBytes(d.first.station, file.name))
        assertEquals(synced.sha256, index.records.single().sha256)
        assertEquals("fake-0", index.records.single().sourceDevice)
        assertTrue(staleLog.isEmpty(), staleLog.toString())
        assertFalse(hasPartials())
    }

    @Test
    fun aWindowThatKeepsFailingFailsOnlyThatFileAndTheNextFileSyncs() = runTest {
        val d = device(backgroundScope)
        // 1 initial attempt and 5 retries, all lossy, so the file is given up. READ 7 and later are intact.
        d.first.dropLeadingChunks = { k -> if (k <= 6) 5 else 0 }

        val first = assertIs<FileOutcome.Failed>(engine.pull(d, 0))
        assertTrue(first.message.contains("lost 6×"), first.message)
        assertEquals(6, d.first.readCount)
        assertTrue(index.records.isEmpty())

        assertIs<FileOutcome.Synced>(engine.pull(d, 1))
        assertEquals(d.first.files[1].name, index.records.single().name)
        assertContentEquals(d.first.files[1].bytes, finalBytes(d.first.station, d.first.files[1].name))
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
        assertTrue(staleLog.isEmpty(), staleLog.toString())
        assertEquals(opened.handle, session.close(opened.handle).handle)
    }

    @Test
    fun aClosedArrivingAfterTheCallerGaveUpIsNotTakenForTheListReply() = runTest {
        val (fake, session) = device(backgroundScope)
        val opened = session.open(fake.files[2].name)
        fake.closedDelayMs = 1_500
        assertNull(withTimeoutOrNull(300) { session.close(opened.handle) }, "close must time out for this test to mean anything")

        val (files, end) = session.list()
        assertEquals(fake.files.size, files.size)
        assertEquals(fake.files.size, end.count)
        assertEquals(1, staleLog.size)
        assertTrue(staleLog[0].contains("after failed CLOSE") && staleLog[0].contains("terminal frame seen"), staleLog[0])
    }

    @Test
    fun differentStationsTransferTheSameNameAtTheSameTime() = runTest {
        val a = device(backgroundScope, "fake-a").also { it.first.chunkDelayMs = 1 }
        val b = device(backgroundScope, "fake-b").also { it.first.chunkDelayMs = 1 }
        val events = ArrayList<String>()
        val first = async { engine.pull(a, 2, station = "station-a") { if (it.bytesDone == 0L) events += "a started" } }
        val second = async { engine.pull(b, 2, station = "station-b") { if (it.bytesDone == 0L) events += "b started" } }
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
        val first = async { engine.pull(a, 0, station = "station-a") }
        val second = async { engine.pull(b, 0, station = "station-a") }
        val outcomes = listOf(first.await(), second.await())
        assertTrue(outcomes.all { it is FileOutcome.Synced }, outcomes.toString())
        assertEquals(1, outcomes.count { (it as FileOutcome.Synced).alreadyArchived })
        val windows = (a.first.files[0].bytes.size + a.first.maxRead - 1) / a.first.maxRead
        assertEquals(windows, a.first.readCount + b.first.readCount)
        assertEquals(1, index.records.size)
        assertEquals(1, index.puts)
        assertFalse(hasPartials())
        assertEquals(0, leases.activeCount())
    }

    @Test
    fun changedProducerBytesNeverReplaceAnExistingVerifiedArchive() = runTest {
        val a = device(backgroundScope, "fake-a")
        assertIs<FileOutcome.Synced>(engine.pull(a, 0))
        val original = a.first.files[0]
        val b = device(backgroundScope, "fake-b")
        val changed = original.bytes.copyOf().also { it[100] = (it[100].toInt() xor 1).toByte() }
        b.first.files[0] = FakeArchiveDevice.FakeFile(original.name, changed)
        assertIs<FileOutcome.Mismatch>(engine.pull(b, 0))
        assertEquals(0, b.first.readCount)
        assertContentEquals(original.bytes, finalBytes(a.first.station, original.name))
        assertEquals(1, index.puts)
    }

    @Test
    fun aCorruptedLocalArchiveIsPreservedAndSurfaced() = runTest {
        val a = device(backgroundScope)
        assertIs<FileOutcome.Synced>(engine.pull(a, 0))
        val file = a.first.files[0]
        val path = ArchiveLayout.finalPath(ArchiveKey(a.first.station, file.name))
        val corrupted = file.bytes.copyOf().also { it[100] = (it[100].toInt() xor 1).toByte() }
        store.files[path] = corrupted
        val b = device(backgroundScope, "fake-b")
        assertIs<FileOutcome.Mismatch>(engine.pull(b, 0))
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

        assertIs<FileOutcome.Synced>(engine.pull(d, 0))

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

        assertIs<FileOutcome.Synced>(engine.pull(d, 0) { progress += it })

        assertEquals(1, d.first.readCount)
        assertEquals(16_384L, progress.first().resumedFrom)
        assertContentEquals(file.bytes, finalBytes(key.station, key.name))
    }

    @Test
    fun aRecordedHashConflictPreservesBytesAndProvenance() = runTest {
        val a = device(backgroundScope)
        assertIs<FileOutcome.Synced>(engine.pull(a, 0))
        val original = index.records.single()
        index.put(original.copy(sha256 = "invalid-recorded-fingerprint"))
        val b = device(backgroundScope, "fake-b")
        assertIs<FileOutcome.Mismatch>(engine.pull(b, 0))
        assertEquals(0, b.first.readCount)
        assertEquals(original.sourceDevice, index.records.single().sourceDevice)
        assertContentEquals(a.first.files[0].bytes, finalBytes(original.station, original.name))
    }

    @Test
    fun aMissingRecordForAVerifiedCopyIsWrittenOnce() = runTest {
        val a = device(backgroundScope)
        assertIs<FileOutcome.Synced>(engine.pull(a, 0))
        index.remove(index.records.single().key)
        val b = device(backgroundScope, "fake-b")
        val again = assertIs<FileOutcome.Synced>(engine.pull(b, 0))
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

        val outcome = assertIs<FileOutcome.Synced>(engine.pull(a, 0))

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

        assertIs<FileOutcome.Mismatch>(engine.pull(a, 0))

        assertContentEquals(other, finalBytes(a.first.station, file.name))
        assertTrue(index.records.isEmpty())
    }

    @Test
    fun aRecordWithoutAFingerprintIsFilledNotRefused() = runTest {
        val a = device(backgroundScope)
        assertIs<FileOutcome.Synced>(engine.pull(a, 0))
        val synced = index.records.single()
        index.put(synced.copy(sha256 = ""))
        val again = assertIs<FileOutcome.Synced>(engine.pull(device(backgroundScope, "fake-b"), 0))
        assertTrue(again.alreadyArchived)
        assertEquals(synced.sha256, index.records.single().sha256)
        assertEquals("fake-0", index.records.single().sourceDevice)
    }

    @Test
    fun theIndexerFillsTheRecordInTheSameWrite() = runTest {
        val indexed = SyncEngine(store, index, indexer = { it.copy(rows = 90, indexedAtEpochMs = 2) }, windowRetryBackoffMs = 5)
        assertIs<FileOutcome.Synced>(indexed.pull(device(backgroundScope), 2))
        assertEquals(1, index.puts)
        assertEquals(90L, index.records.single().rows)
    }

    @Test
    fun anUnsafeNameIsRefusedWithoutOpening() = runTest {
        val d = device(backgroundScope)
        val outcome = engine.download(d.second, d.first.station, "fake", "../escape.parquet", 12, 4096) {}
        assertIs<FileOutcome.Failed>(outcome)
        assertEquals(0, d.first.readCount)
    }

    @Test
    fun aCancelledWaiterDoesNotDropAWriterLeaseOrBlockLaterTransfers() = runTest {
        val d = device(backgroundScope)
        val key = ArchiveKey(d.first.station, d.first.files[0].name)
        val holding = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val owner = launch { leases.withLease(key) { holding.complete(Unit); release.await() } }
        holding.await()
        val waiter = launch(start = CoroutineStart.UNDISPATCHED) { leases.withLease(key) { fail("cancelled waiter ran") } }
        waiter.cancelAndJoin()
        release.complete(Unit)
        owner.join()
        assertEquals(0, leases.activeCount())
        assertIs<FileOutcome.Synced>(engine.pull(d, 0))
    }
}
