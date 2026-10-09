package earth.walkthru.aqsync.data.sync

import earth.walkthru.aqsync.archive.ArchiveKey
import earth.walkthru.aqsync.archive.ArchiveLayout
import earth.walkthru.aqsync.archive.ArchiveRecord
import earth.walkthru.aqsync.data.ble.AuthFailedException
import earth.walkthru.aqsync.data.ble.DeviceBusyException
import earth.walkthru.aqsync.data.ble.DeviceErrorException
import earth.walkthru.aqsync.data.ble.DeviceSession
import earth.walkthru.aqsync.data.protocol.Frame
import earth.walkthru.aqsync.data.protocol.Opcode
import earth.walkthru.aqsync.data.protocol.ParquetVerify
import earth.walkthru.aqsync.data.protocol.ProtocolError
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
    private val engine = SyncEngine(store, index, windowRetryBackoffMs = 5, clock = { 1_000L })

    private fun TestScope.collector() = ArchiveCollector(store, index, engine, testTimeSource)

    private suspend fun TestScope.collect(fake: FakeArchiveDevice, progress: MutableList<CollectionProgress> = ArrayList()) =
        collector().collect(DeviceSession(fake), fake.station, fake.address, fake.maxRead) { progress += it }

    private fun finalBytes(station: String, name: String): ByteArray? = store.files[ArchiveLayout.finalPath(ArchiveKey(station, name))]

    @Test
    fun everyListedFileIsSavedAndCounted() = runTest {
        val fake = FakeArchiveDevice(backgroundScope)
        val progress = ArrayList<CollectionProgress>()

        val summary = collect(fake, progress)

        assertEquals(CollectionSummary(3, emptyList()), summary)
        fake.files.forEach { assertContentEquals(it.bytes, finalBytes(fake.station, it.name)) }
        assertEquals(3, index.records.size)
        assertEquals(CollectionProgress(0, 3, 0, 0, 0.0), progress.first())
        assertEquals(3, progress.last().filesDone)
        assertEquals(fake.files.sumOf { it.bytes.size }.toLong(), progress.last().receivedBytes)
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
}
