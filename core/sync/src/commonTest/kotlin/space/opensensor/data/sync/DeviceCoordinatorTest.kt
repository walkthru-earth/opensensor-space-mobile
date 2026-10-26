package space.opensensor.data.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import space.opensensor.archive.ArchiveKey
import space.opensensor.archive.ArchiveLayout
import space.opensensor.archive.PrunedFile
import space.opensensor.archive.PrunedFiles
import space.opensensor.collection.CollectionLink
import space.opensensor.data.ble.DeviceSession
import space.opensensor.data.protocol.Opcode
import space.opensensor.link.DisconnectCause
import space.opensensor.link.LinkClosedException
import space.opensensor.storage.StorageEntry
import space.opensensor.storage.StorageJanitor
import space.opensensor.storage.StorageRole
import space.opensensor.storage.StorageRoots
import space.opensensor.storage.StorageTree
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The ordering rules of one phone archive that Android and iOS share, run against real writers over
 * in-memory ports. Each test names the guard it pins, and breaking that guard fails it.
 */
class DeviceCoordinatorTest {
    private val store = MemoryArchiveStore()
    private val index = MemoryArchiveIndex()

    /** Tombstones written by the old file cleanup, which the planner and the engine read. */
    private class Marks : PrunedFiles {
        val files = MutableStateFlow(emptyList<PrunedFile>())
        override fun observeAll(): Flow<List<PrunedFile>> = files
        override suspend fun put(file: PrunedFile) {
            files.value = files.value.filterNot { it.key == file.key } + file
        }
        override suspend fun remove(key: ArchiveKey) {
            files.value = files.value.filterNot { it.key == key }
        }
        override suspend fun clear(): Int = files.value.size.also { files.value = emptyList() }
    }

    private fun TestScope.engine(coordinator: DeviceCoordinator) =
        SyncEngine(store, index, windowRetryBackoffMs = 5, clock = { 1_000L }, coordinator = coordinator, io = StandardTestDispatcher(testScheduler))

    private suspend fun TestScope.device(): Pair<FakeArchiveDevice, DeviceSession> {
        val fake = FakeArchiveDevice(backgroundScope)
        return fake to DeviceSession.over(fake, backgroundScope).also { it.link.open() }
    }

    private suspend fun SyncEngine.pull(d: Pair<FakeArchiveDevice, DeviceSession>, i: Int): FileOutcome {
        val f = d.first.files[i]
        return download(d.second, d.first.station, d.first.address, f.name, f.bytes.size.toLong(), d.first.maxRead) {}
    }

    @Test
    fun aSecondCollectionOfTheSameStationIsRefusedUntilTheFirstIsReleased() = runTest {
        val d = device()
        val coordinator = DeviceCoordinator()
        var innerReleased = 0
        var handedBack = 0
        fun link() = CollectionLink(d.second, d.first.station, d.first.maxRead) { innerReleased++ }

        val first = coordinator.admit(link()) { handedBack++ }
        val refused = assertFailsWith<LinkClosedException> { coordinator.admit(link()) }
        assertEquals(DisconnectCause.Busy, refused.disconnectCause)
        assertEquals(1, innerReleased, "the refused link goes back at once")

        first.release()
        assertEquals(1, handedBack)
        assertEquals(2, innerReleased)
        assertEquals(0, coordinator.admittedCount())
        coordinator.admit(link()).release()
        assertEquals(0, coordinator.admittedCount())
    }

    @Test
    fun aFileRemovedWhileItsSyncWaitedForTheLeaseIsNotDownloadedAgain() = runTest {
        val d = device()
        val marks = Marks()
        val coordinator = DeviceCoordinator(marks)
        val file = d.first.files[0]
        val key = ArchiveKey(d.first.station, file.name)
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        // The old file cleanup holds the lease and writes the tombstone, after the sync planned the file.
        launch {
            coordinator.withWriter(key) {
                held.complete(Unit)
                release.await()
                marks.put(PrunedFile(key.station, key.name, file.bytes.size.toLong(), 0, 1))
            }
        }
        held.await()

        val outcome = async { engine(coordinator).pull(d, 0) }
        testScheduler.advanceUntilIdle()
        assertFalse(outcome.isCompleted, "the download waits for the cleanup's lease")
        release.complete(Unit)

        assertEquals(FileOutcome.Removed(file.name), outcome.await())
        assertFalse(outcome.await().failed)
        assertFalse(Opcode.OPEN in d.first.requests, "no OPEN for a removed file")
        assertNull(store.files[ArchiveLayout.finalPath(key)])
        assertNull(index.get(key))
        assertEquals(0, coordinator.activeWriters())
    }

    @Test
    fun theJanitorWaitsForARunningDownloadInsteadOfSweepingItsPartial() = runTest {
        val d = device()
        val coordinator = DeviceCoordinator()
        val file = d.first.files[0]
        val key = ArchiveKey(d.first.station, file.name)
        val reading = CompletableDeferred<Unit>()
        d.first.beforeRead = { reading.complete(Unit) }
        d.first.chunkDelayMs = 1_000

        val outcome = async { engine(coordinator).pull(d, 0) }
        reading.await()
        assertTrue(store.files.keys.any { it.startsWith(ArchiveLayout.PARTIAL + "/") }, "the download has a partial")

        // Every file looks a week old to the janitor, so only the lease keeps the partial.
        val janitor = StorageJanitor(
            roots = StorageRoots(mapOf(StorageRole.Partials to "$BASE/${ArchiveLayout.PARTIAL}")),
            tree = StoreTree(store),
            store = store,
            index = index,
            io = StandardTestDispatcher(testScheduler),
            knownStations = { listOf(key.station) },
            clock = { StorageJanitor.STALE_PARTIAL_AGE_MS * 2 },
            coordinator = coordinator,
        )
        val report = janitor.run()

        assertIs<FileOutcome.Synced>(outcome.await())
        assertEquals(0, report.partialsSwept)
        assertContentEquals(file.bytes, store.files[ArchiveLayout.finalPath(key)])
        assertEquals(0, coordinator.activeWriters())
    }

    /** The janitor's view of [store] under [BASE], every file modified at time zero. */
    private class StoreTree(private val store: MemoryArchiveStore) : StorageTree {
        override fun list(dir: String): List<StorageEntry> {
            val rel = dir.removePrefix("$BASE/")
            return store.files.filterKeys { it.startsWith("$rel/") }.map { (path, bytes) ->
                StorageEntry(path.removePrefix("$rel/"), false, bytes.size.toLong(), 0L)
            }
        }

        override fun delete(path: String) {
            val rel = path.removePrefix("$BASE/")
            store.files.keys.removeAll { it == rel || it.startsWith("$rel/") }
        }
    }

    private companion object {
        const val BASE = "/data/app"
    }
}
