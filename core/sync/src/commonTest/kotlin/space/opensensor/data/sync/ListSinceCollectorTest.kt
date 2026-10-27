package space.opensensor.data.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import space.opensensor.archive.ArchiveKey
import space.opensensor.archive.ArchiveLayout
import space.opensensor.archive.PrunedFile
import space.opensensor.archive.PrunedFiles
import space.opensensor.data.ble.DeviceSession
import space.opensensor.data.ble.openSession
import space.opensensor.data.protocol.Frame
import space.opensensor.data.protocol.ListCursor
import space.opensensor.data.protocol.Opcode
import space.opensensor.data.protocol.ProtocolError
import space.opensensor.storage.FakeStorageBudget
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The incremental `LIST_SINCE` path of [ArchiveCollector], its fallbacks and its audit, over the fake board. */
class ListSinceCollectorTest {
    private val store = MemoryArchiveStore()
    private val index = MemoryArchiveIndex()
    private val cursors = MemoryListCursors()
    private var now = 1_700_000_000_000L

    private class MemoryListCursors : ListCursors {
        val rows = HashMap<String, ListCursorState>()
        override suspend fun get(thing: String): ListCursorState? = rows[thing]
        override suspend fun put(state: ListCursorState) {
            rows[state.thing] = state
        }
    }

    private fun TestScope.collector(pruned: PrunedFiles = PrunedFiles.None) = ArchiveCollector(
        store,
        index,
        SyncEngine(store, index, windowRetryBackoffMs = 5, clock = { 1_000L }, coordinator = DeviceCoordinator(pruned), io = StandardTestDispatcher(testScheduler)),
        FakeStorageBudget(),
        testTimeSource,
        cursors = cursors,
        clock = { now },
    )

    private val links = HashMap<FakeArchiveDevice, DeviceSession>()

    private suspend fun TestScope.session(fake: FakeArchiveDevice): DeviceSession {
        val link = links.getOrPut(fake) { openSession(fake, backgroundScope) }
        return DeviceSession(link.link, link.transport)
    }

    private suspend fun TestScope.collect(fake: FakeArchiveDevice, collector: ArchiveCollector = collector(), onlyName: String? = null) =
        collector.collect(session(fake), fake.thing, fake.address, fake.maxRead, onlyName = onlyName) {}

    private fun TestScope.newBoard(): FakeArchiveDevice = FakeArchiveDevice(backgroundScope).also { it.listSinceEpoch = MOUNT }

    private fun FakeArchiveDevice.addDay(day: Int, seed: Int) = FakeArchiveDevice.FakeFile(
        "thing=$thing/year=2026/month=09/day=$day/data_1000_b41d_15000-15089-90.parquet",
        FakeArchiveDevice.fakeParquet(seed, 12_000),
    ).also { files += it }

    private fun finalBytes(thing: String, name: String): ByteArray? = store.files[ArchiveLayout.finalPath(ArchiveKey(thing, name))]

    @Test
    fun theFirstRunTakesAWholeListingAndSavesTheCursor() = runTest {
        val fake = newBoard()

        val summary = collect(fake)

        assertEquals(3, summary.newFiles)
        assertEquals(listOf(ListCursor.ZERO), fake.listSinceCursors)
        assertFalse(Opcode.LIST in fake.requests)
        assertEquals(ListCursorState(fake.thing, fake.cursorAt(3), now, 0), cursors.rows[fake.thing])
    }

    @Test
    fun theNextRunListsOnlyTheNewFilesFromTheSavedCursor() = runTest {
        val fake = newBoard()
        collect(fake)
        val added = fake.addDay(16, 9)

        val summary = collect(fake)

        assertEquals(listOf(ListCursor.ZERO, fake.cursorAt(3)), fake.listSinceCursors)
        assertEquals(4, summary.files, "the three files already here still count, as after a whole listing")
        assertEquals(1, summary.newFiles)
        assertContentEquals(added.bytes, finalBytes(fake.thing, added.name))
        assertEquals(ListCursorState(fake.thing, fake.cursorAt(4), now, 1), cursors.rows[fake.thing])
    }

    @Test
    fun aRewrittenFileIsListedAgainAndStillVerified() = runTest {
        val fake = newBoard()
        collect(fake)
        val grown = fake.files[2]
        fake.files[2] = FakeArchiveDevice.FakeFile(grown.name, FakeArchiveDevice.fakeParquet(4, 10_400))
        fake.touch(grown.name)

        val summary = collect(fake)

        assertEquals(3, summary.files)
        assertEquals(listOf(FileNotSaved(grown.name, FileProblem.LOCAL_CONFLICT)), summary.notSaved)
        assertContentEquals(grown.bytes, finalBytes(fake.thing, grown.name), "a saved file is never replaced")
        assertEquals(fake.cursorAt(4), cursors.rows[fake.thing]?.cursor, "a conflict a retry cannot fix still moves the cursor")
    }

    @Test
    fun aBoardWithoutLsInStatusGetsPlainList() = runTest {
        val fake = newBoard().also { it.listSinceEpoch = null }

        val summary = collect(fake)

        assertEquals(3, summary.newFiles)
        assertTrue(Opcode.LIST in fake.requests)
        assertFalse(Opcode.LIST_SINCE in fake.requests)
        assertNull(cursors.rows[fake.thing])
    }

    @Test
    fun anUnknownOpcodeErrorFallsBackToPlainList() = runTest {
        val fake = newBoard()
        fake.refuseListSince = { Frame.Error(Opcode.LIST_SINCE, ProtocolError.UNKNOWN_OPCODE.code, "") }

        val summary = collect(fake)

        assertEquals(3, summary.newFiles)
        assertEquals(listOf(Opcode.LIST_SINCE, Opcode.LIST), fake.requests.filter { it == Opcode.LIST || it == Opcode.LIST_SINCE })
        assertNull(cursors.rows[fake.thing])
    }

    @Test
    fun aZeroLsMeansNoCardCursorAndTakesAWholeListing() = runTest {
        val fake = newBoard()
        collect(fake)
        fake.listSinceEpoch = 0

        collect(fake)

        assertEquals(listOf(ListCursor.ZERO, ListCursor.ZERO), fake.listSinceCursors)
    }

    @Test
    fun aChangedLsSendsTheZeroCursor() = runTest {
        val fake = newBoard()
        collect(fake)
        fake.listSinceEpoch = MOUNT + 1
        fake.addDay(16, 9)

        val summary = collect(fake)

        assertEquals(listOf(ListCursor.ZERO, ListCursor.ZERO), fake.listSinceCursors)
        assertEquals(4, summary.files)
        assertEquals(MOUNT + 1, cursors.rows[fake.thing]?.cursor?.mountEpoch)
        assertEquals(0, cursors.rows[fake.thing]?.sinceWhole)
    }

    @Test
    fun aCursorTheBoardCannotReadIsRetriedWithTheZeroCursor() = runTest {
        val fake = newBoard()
        collect(fake)
        fake.refuseListSince = { if (it.isZero) null else Frame.Error(Opcode.LIST_SINCE, ProtocolError.MALFORMED_REQUEST.code, "cursor") }

        val summary = collect(fake)

        assertEquals(listOf(ListCursor.ZERO, fake.cursorAt(3), ListCursor.ZERO), fake.listSinceCursors)
        assertEquals(3, summary.files)
        assertEquals(0, cursors.rows[fake.thing]?.sinceWhole)
    }

    @Test
    fun aBoardMarkedFullCountsAsAWholeListing() = runTest {
        val fake = newBoard()
        collect(fake)
        collect(fake)
        fake.listSinceFull = { true }

        val summary = collect(fake)

        assertEquals(fake.cursorAt(3), fake.listSinceCursors.last())
        assertEquals(3, summary.files)
        assertEquals(0, cursors.rows[fake.thing]?.sinceWhole)
    }

    @Test
    fun theAuditTakesAWholeListingOnceADay() = runTest {
        val fake = newBoard()
        collect(fake)
        now += AUDIT_INTERVAL_MS - 1
        collect(fake)
        now += 1

        collect(fake)

        assertEquals(listOf(true, false, true), fake.listSinceCursors.map { it.isZero })
        assertEquals(ListCursorState(fake.thing, fake.cursorAt(3), now, 0), cursors.rows[fake.thing])
    }

    @Test
    fun theAuditTakesAWholeListingEveryTenthRun() = runTest {
        val fake = newBoard()
        repeat(AUDIT_EVERY_SYNCS + 1) { collect(fake) }

        assertEquals(listOf(true) + List(AUDIT_EVERY_SYNCS - 1) { false } + true, fake.listSinceCursors.map { it.isZero })
        assertEquals(0, cursors.rows[fake.thing]?.sinceWhole)
    }

    @Test
    fun aClockThatMovedBackTakesAWholeListing() = runTest {
        val fake = newBoard()
        collect(fake)
        now -= 1

        collect(fake)

        assertEquals(listOf(true, true), fake.listSinceCursors.map { it.isZero })
    }

    @Test
    fun aPhoneWithoutFilesOfTheStationTakesAWholeListing() = runTest {
        val fake = newBoard()
        collect(fake)
        index.records.map { it.key }.forEach { index.remove(it) }
        store.files.clear()

        val summary = collect(fake)

        assertEquals(listOf(true, true), fake.listSinceCursors.map { it.isZero })
        assertEquals(3, summary.newFiles)
    }

    @Test
    fun aFileThatFailedKeepsTheCursorSoTheNextRunListsItAgain() = runTest {
        val fake = newBoard()
        collect(fake)
        val added = fake.addDay(16, 9)
        fake.refuseOpen = { name -> if (name == added.name) Frame.Error(Opcode.OPEN, ProtocolError.OPEN_FAILED.code, "") else null }

        val failed = collect(fake)
        assertFalse(failed.complete)
        assertEquals(ListCursorState(fake.thing, fake.cursorAt(3), now, 0), cursors.rows[fake.thing])

        fake.refuseOpen = { null }
        val retried = collect(fake)

        assertEquals(listOf(ListCursor.ZERO, fake.cursorAt(3), fake.cursorAt(3)), fake.listSinceCursors)
        assertEquals(1, retried.newFiles)
        assertContentEquals(added.bytes, finalBytes(fake.thing, added.name))
    }

    @Test
    fun aRunForOneFileUsesPlainListAndLeavesTheCursor() = runTest {
        val fake = newBoard()
        collect(fake)
        val added = fake.addDay(16, 9)

        collect(fake, onlyName = added.name)

        assertEquals(listOf(ListCursor.ZERO), fake.listSinceCursors)
        assertTrue(Opcode.LIST in fake.requests)
        assertEquals(fake.cursorAt(3), cursors.rows[fake.thing]?.cursor)
    }

    @Test
    fun anIncrementalListingKeepsRemovedMarksItDoesNotName() = runTest {
        val fake = newBoard()
        val gone = PrunedFile(fake.thing, "thing=${fake.thing}/year=2020/month=01/day=01/gone.parquet", 10, 0, 1)
        val marks = MutableStateFlow(listOf(gone))
        val pruned = object : PrunedFiles {
            override fun observeAll(): Flow<List<PrunedFile>> = marks
            override suspend fun put(file: PrunedFile) {
                marks.value = marks.value.filterNot { it.key == file.key } + file
            }
            override suspend fun remove(key: ArchiveKey) {
                marks.value = marks.value.filterNot { it.key == key }
            }
            override suspend fun clear(): Int = marks.value.size.also { marks.value = emptyList() }
        }
        // Saved earlier with a fresh cursor, so this run is incremental.
        collect(fake)
        marks.value = listOf(gone)

        collect(fake, collector(pruned))
        assertEquals(listOf(gone), marks.value)

        now += AUDIT_INTERVAL_MS
        collect(fake, collector(pruned))
        assertEquals(emptyList(), marks.value)
    }

    private companion object {
        const val MOUNT = 0x1234_5678L
    }
}
