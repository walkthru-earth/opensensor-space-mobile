package earth.walkthru.opensensor.files

import earth.walkthru.aqsync.archive.ArchiveIndex
import earth.walkthru.aqsync.archive.ArchiveKey
import earth.walkthru.aqsync.archive.ArchiveRecord
import earth.walkthru.aqsync.archive.ArchiveStore
import earth.walkthru.aqsync.archive.ExportEntry
import earth.walkthru.aqsync.archive.ExportRun
import earth.walkthru.aqsync.archive.ExportSink
import earth.walkthru.aqsync.archive.ExportSpace
import earth.walkthru.aqsync.archive.PartialMeta
import earth.walkthru.aqsync.archive.PartialWriter
import earth.walkthru.aqsync.archive.StagedFile
import earth.walkthru.aqsync.data.history.parquet.ByteArrayReader
import earth.walkthru.aqsync.data.history.parquet.ByteReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FilesControllerTest {
    private val nameA = "station=s/year=2026/month=09/day=17/data_0900_b_1-2-1.parquet"
    private val nameB = "station=s/year=2026/month=09/day=17/data_1000_b_3-4-1.parquet"
    private val bytesA = "PAR1-a-PAR1".encodeToByteArray()
    private val bytesB = "PAR1-b-PAR1".encodeToByteArray()

    private class Store(val finals: Map<ArchiveKey, ByteArray>) : ArchiveStore {
        var reads = 0
        override suspend fun partialLengths(station: String): Map<String, Long> = emptyMap()
        override suspend fun openPartial(key: ArchiveKey, meta: PartialMeta): PartialWriter = error("exports never write the archive")
        override suspend fun discardPartial(key: ArchiveKey) = error("exports never write the archive")
        override suspend fun exists(key: ArchiveKey): Boolean = key in finals
        override fun reader(key: ArchiveKey, partial: Boolean): ByteReader {
            check(!partial)
            reads++
            return ByteArrayReader(finals.getValue(key))
        }
        override suspend fun commit(key: ArchiveKey) = error("exports never write the archive")
        override suspend fun listFinal(station: String?): List<ArchiveKey> = finals.keys.filter { station == null || it.station == station }
    }

    private class Index(records: List<ArchiveRecord>) : ArchiveIndex {
        val rows = MutableStateFlow(records)
        override fun observeAll(): Flow<List<ArchiveRecord>> = rows.map { r -> r.sortedBy { it.name } }
        override fun observeStation(station: String): Flow<List<ArchiveRecord>> = rows.map { r -> r.filter { it.station == station } }
        override suspend fun get(key: ArchiveKey): ArchiveRecord? = rows.value.firstOrNull { it.key == key }
        override suspend fun put(record: ArchiveRecord) = error("read only")
        override suspend fun remove(key: ArchiveKey) = error("read only")
        override suspend fun unindexed(limit: Int): List<ArchiveRecord> = emptyList()
        override suspend fun timeline(station: String, fromNs: Long, toNs: Long): List<ArchiveRecord> = emptyList()
        override suspend fun indexedForStation(station: String): List<ArchiveRecord> = emptyList()
        override suspend fun stations(): List<String> = rows.value.map { it.station }.distinct()
    }

    /** Runs in memory, each one a map of name to bytes, with a flag once deleted. */
    private class Space : ExportSpace {
        val runs = mutableListOf<Run>()

        inner class Run(val id: Int) : ExportRun {
            val files = LinkedHashMap<String, ByteArray>()
            var deleted = false
            override fun path(name: String) = "/run$id/$name"
            override fun create(name: String): ExportSink = object : ExportSink {
                private val out = mutableListOf<Byte>()
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    for (i in offset until offset + length) out += bytes[i]
                }
                override fun close() {
                    files[name] = out.toByteArray()
                }
            }
            override fun delete() {
                deleted = true
            }
        }

        override fun newRun(): ExportRun {
            runs.forEach { it.deleted = true }
            return Run(runs.size).also { runs += it }
        }
    }

    private class Presenter(val closesNow: Boolean = true, val fail: Boolean = false, val waitForever: Boolean = false) : ExportPresenter {
        val shared = mutableListOf<Pair<List<StagedFile>, ShareKind>>()
        val saved = mutableListOf<StagedFile>()
        override suspend fun share(files: List<StagedFile>, kind: ShareKind): Boolean {
            if (fail) error("no sheet")
            shared += files to kind
            if (waitForever) awaitCancellation()
            return closesNow
        }
        override suspend fun save(file: StagedFile): Boolean {
            if (fail) error("no picker")
            saved += file
            if (waitForever) awaitCancellation()
            return true
        }
    }

    private fun record(station: String, name: String, bytes: ByteArray) =
        ArchiveRecord(station = station, name = name, bytes = bytes.size.toLong(), crc32 = 1, sha256 = "x", syncedAtEpochMs = 1_000, sourceDevice = station)

    private class Fixture(scope: TestScope, val store: Store, val index: Index, owner: String?, daily: (suspend () -> List<ExportEntry>)? = null) {
        val space = Space()
        val owner = MutableStateFlow(owner)

        /** The controller's scope, cancelled to stand for a screen that goes away mid export. */
        val job = Job(scope.backgroundScope.coroutineContext[Job])

        // Background work alone never counts for advanceUntilIdle, so the controller runs unconfined.
        val controller = FilesController(
            CoroutineScope(scope.backgroundScope.coroutineContext + job + UnconfinedTestDispatcher(scope.testScheduler)),
            store,
            index,
            space,
            this.owner,
            daily,
            now = { 1_790_000_000_000L },
            io = UnconfinedTestDispatcher(scope.testScheduler),
        )
    }

    private fun TestScope.fixture(owner: String? = "s", daily: (suspend () -> List<ExportEntry>)? = null): Fixture {
        val a = record("s", nameA, bytesA)
        val b = record("s", nameB, bytesB)
        val other = record("t", nameA, bytesB)
        val store = Store(mapOf(a.key to bytesA, b.key to bytesB, other.key to bytesB))
        return Fixture(this, store, Index(listOf(a, b, other)), owner, daily)
    }

    @Test
    fun sharingOneFileStagesACopyAndRemovesTheRunWhenTheSheetCloses() = runTest {
        val f = fixture()
        advanceUntilIdle()
        val p = Presenter()
        f.controller.shareFile(f.controller.archived(nameA)!!, p)
        advanceUntilIdle()
        val (files, kind) = p.shared.single()
        assertEquals(ShareKind.FILE, kind)
        assertEquals("data_0900_b_1-2-1.parquet", files.single().name)
        assertContentEquals(bytesA, f.space.runs.single().files.values.single())
        assertTrue(f.space.runs.single().deleted)
        assertFalse(f.controller.state.value.exporting)
    }

    @Test
    fun aSheetWithoutACloseSignalLeavesTheRunForTheNextExport() = runTest {
        val f = fixture()
        advanceUntilIdle()
        val p = Presenter(closesNow = false)
        f.controller.shareAll(p)
        advanceUntilIdle()
        assertEquals(2, p.shared.single().first.size)
        assertFalse(f.space.runs.single().deleted)
        f.controller.shareAll(p)
        advanceUntilIdle()
        assertTrue(f.space.runs[0].deleted)
        assertEquals(2, f.space.runs.size)
    }

    @Test
    fun savingTheZipNamesItByTheClockAndRemovesTheRun() = runTest {
        val f = fixture()
        advanceUntilIdle()
        val p = Presenter()
        f.controller.saveZip(p)
        advanceUntilIdle()
        val zip = p.saved.single()
        assertTrue(zip.name.startsWith("opensensor-space-archive-") && zip.name.endsWith(".zip"), zip.name)
        assertTrue(f.space.runs.single().deleted)
    }

    @Test
    fun aFailedExportReportsOnceAndFreesTheScreen() = runTest {
        val f = fixture()
        val messages = mutableListOf<FilesMessage>()
        collectMessages(f.controller, messages)
        advanceUntilIdle()
        f.controller.shareZip(Presenter(fail = true))
        advanceUntilIdle()
        assertEquals(listOf(FilesMessage.EXPORT_FAILED), messages)
        assertFalse(f.controller.state.value.exporting)
    }

    @Test
    fun aSheetThatThrowsStillRemovesTheStagedRun() = runTest {
        val f = fixture()
        advanceUntilIdle()
        f.controller.shareZip(Presenter(fail = true))
        advanceUntilIdle()
        assertTrue(f.space.runs.single().deleted)
        f.controller.saveZip(Presenter(fail = true))
        advanceUntilIdle()
        assertEquals(2, f.space.runs.size)
        assertTrue(f.space.runs.all { it.deleted })
        assertFalse(f.controller.state.value.exporting)
    }

    @Test
    fun aCancelledWaitForTheSheetRemovesTheStagedRun() = runTest {
        val f = fixture()
        advanceUntilIdle()
        f.controller.saveZip(Presenter(waitForever = true))
        advanceUntilIdle()
        assertTrue(f.controller.state.value.exporting)
        assertFalse(f.space.runs.single().deleted)
        f.job.cancel()
        advanceUntilIdle()
        assertTrue(f.space.runs.single().deleted)
    }

    @Test
    fun noDerivedDailyFilesSaysSo() = runTest {
        val f = fixture(daily = { emptyList() })
        val messages = mutableListOf<FilesMessage>()
        collectMessages(f.controller, messages)
        advanceUntilIdle()
        assertTrue(f.controller.state.value.dailyAvailable)
        val p = Presenter()
        f.controller.shareDaily(p)
        advanceUntilIdle()
        assertEquals(listOf(FilesMessage.NO_DAILY), messages)
        assertTrue(p.shared.isEmpty())
    }

    @Test
    fun theOwnerDecidesWhichCopyAListedNameMeans() = runTest {
        val f = fixture(owner = "t")
        advanceUntilIdle()
        assertEquals("t", f.controller.archived(nameA)?.station)
        assertNull(f.controller.archived(nameB))
        assertEquals(listOf("t"), f.controller.state.value.shown.map { it.station })
        f.owner.value = null
        advanceUntilIdle()
        // Without an owner a listed name never falls back to another sensor's copy.
        assertNull(f.controller.archived(nameA))
        assertEquals(3, f.controller.state.value.shown.size)
        assertFalse(f.controller.state.value.dailyAvailable)
    }

    @Test
    fun sensorExportsAndCountsStayWithinItsStationButPhoneArchiveIncludesAll() = runTest {
        val f = fixture(owner = "t")
        advanceUntilIdle()
        assertEquals(1, f.controller.state.value.shown.size)
        assertEquals(bytesB.size.toLong(), f.controller.state.value.archivedBytes)
        val sensorPresenter = Presenter()
        f.controller.shareAll(sensorPresenter)
        advanceUntilIdle()
        assertEquals(1, sensorPresenter.shared.single().first.size)
        assertEquals(1, f.space.runs.last().files.size)
        val zipPresenter = Presenter()
        f.controller.shareZip(zipPresenter)
        advanceUntilIdle()
        assertEquals(1, zipPresenter.shared.single().first.size)
        // A separate phone-archive entry has no owner and still exports every station.
        f.owner.value = null
        advanceUntilIdle()
        assertEquals(3, f.controller.state.value.shown.size)
        val phonePresenter = Presenter()
        f.controller.shareAll(phonePresenter)
        advanceUntilIdle()
        assertEquals(3, phonePresenter.shared.single().first.size)
    }

    @Test
    fun exportsOnlyReadTheArchive() = runTest {
        val f = fixture()
        advanceUntilIdle()
        val p = Presenter()
        f.controller.shareZip(p)
        advanceUntilIdle()
        assertEquals(ShareKind.ARCHIVE, p.shared.single().second)
        assertTrue(f.store.reads > 0)
        assertEquals(3, f.index.rows.value.size)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.collectMessages(controller: FilesController, into: MutableList<FilesMessage>) {
    CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)).launch { controller.messages.collect { into += it } }
}
