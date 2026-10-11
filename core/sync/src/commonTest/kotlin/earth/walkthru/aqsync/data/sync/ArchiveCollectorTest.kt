package earth.walkthru.aqsync.data.sync

import earth.walkthru.aqsync.archive.ArchiveKey
import earth.walkthru.aqsync.archive.ArchiveLayout
import earth.walkthru.aqsync.archive.ArchiveRecord
import earth.walkthru.aqsync.archive.ArchiveStore
import earth.walkthru.aqsync.archive.PartialMeta
import earth.walkthru.aqsync.archive.PartialWriter
import earth.walkthru.aqsync.data.ble.AuthFailedException
import earth.walkthru.aqsync.data.ble.DeviceBusyException
import earth.walkthru.aqsync.data.ble.DeviceErrorException
import earth.walkthru.aqsync.data.ble.DeviceSession
import earth.walkthru.aqsync.data.ble.openSession
import earth.walkthru.aqsync.data.protocol.Frame
import earth.walkthru.aqsync.data.protocol.Opcode
import earth.walkthru.aqsync.data.protocol.ParquetVerify
import earth.walkthru.aqsync.data.protocol.ProtocolError
import earth.walkthru.aqsync.storage.FakeStorageBudget
import earth.walkthru.aqsync.storage.StorageBudget
import earth.walkthru.aqsync.storage.StorageClass
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The whole collection both phones run on top of the per-file engine, over in-memory ports. */
class ArchiveCollectorTest {
    private val store = MemoryArchiveStore()
    private val index = MemoryArchiveIndex()

    private fun TestScope.engine() = SyncEngine(store, index, windowRetryBackoffMs = 5, clock = { 1_000L }, io = StandardTestDispatcher(testScheduler))

    /** One link per fake, as one phone keeps one session per connection. Each run takes a fresh view. */
    private val links = HashMap<FakeArchiveDevice, DeviceSession>()

    private suspend fun TestScope.session(fake: FakeArchiveDevice): DeviceSession {
        val link = links.getOrPut(fake) { openSession(fake, backgroundScope) }
        return DeviceSession(link.link, link.transport)
    }

    private fun TestScope.collector(budget: StorageBudget = FakeStorageBudget()) = ArchiveCollector(store, index, engine(), budget, testTimeSource)

    private suspend fun TestScope.collect(
        fake: FakeArchiveDevice,
        progress: MutableList<CollectionProgress> = ArrayList(),
        budget: StorageBudget = FakeStorageBudget(),
    ) = collector(budget).collect(session(fake), fake.station, fake.address, fake.maxRead) { progress += it }

    private fun finalBytes(station: String, name: String): ByteArray? = store.files[ArchiveLayout.finalPath(ArchiveKey(station, name))]

    @Test
    fun everyListedFileIsSavedAndCounted() = runTest {
        val fake = FakeArchiveDevice(backgroundScope)
        val progress = ArrayList<CollectionProgress>()

        val summary = collect(fake, progress)

        assertEquals(CollectionSummary(3, emptyList()), summary)
        fake.files.forEach { assertContentEquals(it.bytes, finalBytes(fake.station, it.name)) }
        assertEquals(3, index.records.size)
        assertEquals(CollectionProgress(0, 3, 0, 0, 0.0, fake.files.sumOf { it.bytes.size }.toLong()), progress.first())
        assertEquals(3, progress.last().filesDone)
        assertEquals(fake.files.sumOf { it.bytes.size }.toLong(), progress.last().receivedBytes)
        assertEquals(progress.last().receivedBytes, progress.last().bytesTotal)
        assertTrue(progress.zipWithNext().all { (a, b) -> b.receivedBytes >= a.receivedBytes }, "received bytes never go back")
    }

    @Test
    fun aSecondRunReadsNothing() = runTest {
        val fake = FakeArchiveDevice(backgroundScope)
        collect(fake)
        val reads = fake.readCount
        val progress = ArrayList<CollectionProgress>()

        val summary = collect(fake, progress)

        assertTrue(summary.complete)
        assertEquals(reads, fake.readCount)
        assertEquals(CollectionProgress(3, 3, 0, 0, 0.0), progress.single())
    }

    @Test
    fun selectedFileSyncOnlyReadsThatFile() = runTest {
        val fake = FakeArchiveDevice(backgroundScope)
        val chosen = fake.files[1]
        val progress = ArrayList<CollectionProgress>()

        val summary = collector().collect(session(fake), fake.station, fake.address, fake.maxRead, chosen.name) {
            progress += it
        }

        assertEquals(CollectionSummary(1, emptyList()), summary)
        assertContentEquals(chosen.bytes, finalBytes(fake.station, chosen.name))
        assertEquals(1, index.records.size)
        assertEquals(chosen.bytes.size.toLong(), progress.last().bytesTotal)
    }

    @Test
    fun aDifferentLocalCopyIsReportedAndKept() = runTest {
        val fake = FakeArchiveDevice(backgroundScope)
        val file = fake.files[0]
        val local = FakeArchiveDevice.fakeParquet(9, 100)
        store.files[ArchiveLayout.finalPath(ArchiveKey(fake.station, file.name))] = local
        index.put(ArchiveRecord(fake.station, file.name, local.size.toLong(), ParquetVerify.crc32(local), "", 1, "other"))

        val summary = collect(fake)

        assertEquals(listOf(FileNotSaved(file.name, FileProblem.LOCAL_CONFLICT)), summary.notSaved)
        assertContentEquals(local, finalBytes(fake.station, file.name))
        assertContentEquals(fake.files[1].bytes, finalBytes(fake.station, fake.files[1].name))
    }

    @Test
    fun anUnsafeNameAndARefusedFileFailAlone() = runTest {
        val fake = FakeArchiveDevice(backgroundScope)
        fake.files += FakeArchiveDevice.FakeFile("../escape.parquet", FakeArchiveDevice.fakeParquet(4, 64))
        val gone = "station=${fake.station}/year=2026/month=09/day=16/data_0000_b41d_1-2-2.parquet"
        val listed = fake.files.toList()
        fake.files += FakeArchiveDevice.FakeFile(gone, FakeArchiveDevice.fakeParquet(5, 64))
        fake.beforeOpen = { name -> if (name == gone) fake.files.removeAll { it.name == gone } }

        val summary = collect(fake)

        assertEquals(
            listOf(FileNotSaved("../escape.parquet", FileProblem.INVALID_ENTRY), FileNotSaved(gone, FileProblem.REFUSED)),
            summary.notSaved,
        )
        listed.dropLast(1).forEach { assertContentEquals(it.bytes, finalBytes(fake.station, it.name)) }
    }

    @Test
    fun aWindowThatKeepsFailingFailsOnlyItsFile() = runTest {
        val fake = FakeArchiveDevice(backgroundScope)
        // File 0 needs READs 1 to 6 to give up. The other files read cleanly afterwards.
        fake.dropLeadingChunks = { k -> if (k <= WindowRetryPolicy.RETRIES + 1) 1 else 0 }

        val summary = collect(fake)

        assertEquals(listOf(FileNotSaved(fake.files[0].name, FileProblem.FAILED)), summary.notSaved)
        assertContentEquals(fake.files[1].bytes, finalBytes(fake.station, fake.files[1].name))
    }

    @Test
    fun aTokenRefusedMidSyncEndsTheRunInsteadOfRefusingEveryFile() = runTest {
        val fake = FakeArchiveDevice(backgroundScope)
        var opens = 0
        fake.refuseOpen = { if (++opens == 2) Frame.Error(0, ProtocolError.AUTH_FAILED.code, "") else null }

        assertFailsWith<AuthFailedException> { collect(fake) }
        assertEquals(2, opens, "no OPEN after the refusal")
        assertEquals(1, store.files.keys.count { it.startsWith(ArchiveLayout.ARCHIVE + "/") })
    }

    @Test
    fun aBusySensorOrASessionErrorEndsTheRun() = runTest {
        val fake = FakeArchiveDevice(backgroundScope)
        fake.refuseOpen = { Frame.Error(Opcode.OPEN, ProtocolError.BUSY.code, "") }
        assertFailsWith<DeviceBusyException> { collect(fake) }

        fake.refuseOpen = { Frame.Error(0, ProtocolError.MALFORMED_REQUEST.code, "") }
        assertFailsWith<DeviceErrorException> { collect(fake) }
        assertTrue(store.files.keys.none { it.startsWith(ArchiveLayout.ARCHIVE + "/") })
    }

    @Test
    fun aStorageFaultFailsOnlyItsFileAndTheRunGoesOn() = runTest {
        val fake = FakeArchiveDevice(backgroundScope)
        val full = fake.files[0].name
        val stuck = fake.files[1].name
        store.failWrite = { key -> if (key.name == full) IllegalStateException("No space left on device") else null }
        store.failCommit = { key -> if (key.name == stuck) IllegalStateException("destination is a directory") else null }

        val summary = collect(fake)

        assertEquals(listOf(FileNotSaved(full, FileProblem.STORAGE), FileNotSaved(stuck, FileProblem.STORAGE)), summary.notSaved)
        assertContentEquals(fake.files[2].bytes, finalBytes(fake.station, fake.files[2].name))
        assertEquals(1, index.records.size, "no receipt for a file that was not stored")
        assertTrue(store.files.keys.any { it == ArchiveLayout.partialPath(ArchiveKey(fake.station, stuck)) }, "verified bytes stay for the next run")
    }

    private fun budget(free: Long) = FakeStorageBudget(mapOf(StorageClass.Durable to free))

    private val plenty get() = budget(1L shl 30)

    @Test
    fun aRunThatFitsReservesItsBytesBeforeTheFirstOpen() = runTest {
        val fake = FakeArchiveDevice(backgroundScope)
        val budget = plenty

        collect(fake, budget = budget)

        val missing = fake.files.sumOf { it.bytes.size.toLong() }
        assertEquals(StorageClass.Durable to missing + ARCHIVE_HEADROOM_BYTES, budget.reservations.first())
    }

    @Test
    fun aRunThatDoesNotFitOpensNothingAndRemovesNothing() = runTest {
        val fake = FakeArchiveDevice(backgroundScope)
        val kept = fake.files[0]
        assertTrue(collector().collect(session(fake), fake.station, fake.address, fake.maxRead, kept.name) {}.complete)
        val opensBefore = fake.requests.count { it == Opcode.OPEN }

        val e = assertFailsWith<InsufficientSpaceException> { collect(fake, budget = budget(1_000)) }

        val missing = fake.files.drop(1).sumOf { it.bytes.size.toLong() }
        assertEquals(missing + ARCHIVE_HEADROOM_BYTES, e.needed)
        assertEquals(1_000L, e.available)
        assertEquals(opensBefore, fake.requests.count { it == Opcode.OPEN }, "no OPEN for a run that does not fit")
        assertEquals(Opcode.LIST, fake.requests.last())
        assertContentEquals(kept.bytes, finalBytes(fake.station, kept.name))
        assertEquals(kept.name, index.records.single().name)
    }

    @Test
    fun aDiskThatFillsMidFileEndsTheRunAndTheNextRunResumesTheKeptPartial() = runTest {
        val fake = FakeArchiveDevice(backgroundScope)
        val budget = budget(1L shl 30)
        val full = FillingStore(store, limit = 3_000) { budget.free[StorageClass.Durable] = 1_000 }

        val e = assertFailsWith<InsufficientSpaceException> {
            ArchiveCollector(store = full, index, engine(full), budget, testTimeSource)
                .collect(session(fake), fake.station, fake.address, fake.maxRead) {}
        }
        assertEquals(1_000L, e.available)
        assertEquals(1, fake.requests.count { it == Opcode.OPEN }, "the run ended with the first file")
        val (name, held) = store.partialLengths(fake.station).entries.single()
        assertTrue(held > 0, "the partial keeps the bytes written before the disk filled")
        assertTrue(index.records.isEmpty())

        budget.free[StorageClass.Durable] = 1L shl 30
        full.armed = false
        val readsBefore = fake.readOffsets.size
        val summary = collect(fake, budget = budget)

        assertEquals(CollectionSummary(3, emptyList()), summary)
        assertEquals(held, fake.readOffsets[readsBefore], "the first READ of the next run starts at the partial, reads ${fake.readOffsets}")
        assertContentEquals(fake.files.single { it.name == name }.bytes, finalBytes(fake.station, name))
        assertTrue(store.partialLengths(fake.station).isEmpty())
    }

    @Test
    fun aStorageFaultWithRoomLeftFailsOnlyItsFile() = runTest {
        val fake = FakeArchiveDevice(backgroundScope)
        val refused = fake.files[1].name
        store.failWrite = { key -> if (key.name == refused) IllegalStateException("permission denied") else null }

        val summary = collect(fake, budget = plenty)

        assertEquals(listOf(FileNotSaved(refused, FileProblem.STORAGE)), summary.notSaved)
        fake.files.filter { it.name != refused }.forEach { assertContentEquals(it.bytes, finalBytes(fake.station, it.name)) }
        assertEquals(2, index.records.size)
    }

    private fun TestScope.engine(archive: ArchiveStore) =
        SyncEngine(archive, index, windowRetryBackoffMs = 5, clock = { 1_000L }, io = StandardTestDispatcher(testScheduler))

    /** Fails a write that would pass [limit] bytes in all, as a disk that fills does, and calls [onFull] first. */
    private class FillingStore(private val inner: ArchiveStore, private val limit: Long, private val onFull: () -> Unit) : ArchiveStore by inner {
        var armed = true
        private var written = 0L

        override suspend fun openPartial(key: ArchiveKey, meta: PartialMeta): PartialWriter {
            val writer = inner.openPartial(key, meta)
            return object : PartialWriter by writer {
                override fun writeAt(offset: Long, payload: ByteArray) {
                    if (armed && written + payload.size > limit) {
                        onFull()
                        throw IllegalStateException("No space left on device")
                    }
                    writer.writeAt(offset, payload)
                    written += payload.size
                }
            }
        }
    }
}
