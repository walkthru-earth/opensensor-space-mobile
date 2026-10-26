package space.opensensor.files

import kotlinx.coroutines.CompletableDeferred
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
import space.opensensor.archive.ArchiveFileMissingException
import space.opensensor.archive.ArchiveIndex
import space.opensensor.archive.ArchiveKey
import space.opensensor.archive.ArchiveRecord
import space.opensensor.archive.ArchiveStore
import space.opensensor.archive.ExportEntry
import space.opensensor.archive.ExportRun
import space.opensensor.archive.ExportSink
import space.opensensor.archive.ExportSpace
import space.opensensor.archive.PartialMeta
import space.opensensor.archive.PartialWriter
import space.opensensor.archive.StagedFile
import space.opensensor.data.history.parquet.ByteArrayReader
import space.opensensor.data.history.parquet.ByteReader
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

        /** Finals removed after the listing, such as by the old format cleanup. */
        val gone = mutableSetOf<ArchiveKey>()
        override suspend fun partialLengths(thing: String): Map<String, Long> = emptyMap()
        override suspend fun openPartial(key: ArchiveKey, meta: PartialMeta): PartialWriter = error("exports never write the archive")
        override suspend fun discardPartial(key: ArchiveKey) = error("exports never write the archive")
        override suspend fun exists(key: ArchiveKey): Boolean = key in finals
        override fun reader(key: ArchiveKey, partial: Boolean): ByteReader {
            check(!partial)
            reads++
            if (key in gone) throw ArchiveFileMissingException("gone: ${key.name}")
            return ByteArrayReader(finals.getValue(key))
        }
        override suspend fun commit(key: ArchiveKey) = error("exports never write the archive")
        override suspend fun listFinal(thing: String?): List<ArchiveKey> = finals.keys.filter { thing == null || it.thing == thing }
    }

    private class Index(records: List<ArchiveRecord>) : ArchiveIndex {
        val rows = MutableStateFlow(records)

        /** The scope of each subscription, a thing or null for every record. */
        val observed = mutableListOf<String?>()
        override fun observeAll(): Flow<List<ArchiveRecord>> = rows.map { r -> r.sortedBy { it.name } }.also { observed += null }
        override fun observeThing(thing: String): Flow<List<ArchiveRecord>> = rows.map { r -> r.filter { it.thing == thing } }.also { observed += thing }
        override suspend fun get(key: ArchiveKey): ArchiveRecord? = rows.value.firstOrNull { it.key == key }
        override suspend fun put(record: ArchiveRecord) = error("read only")
        override suspend fun remove(key: ArchiveKey) = error("read only")
        override suspend fun unindexed(limit: Int): List<ArchiveRecord> = emptyList()
        override suspend fun timeline(thing: String, fromNs: Long, toNs: Long): List<ArchiveRecord> = emptyList()
        override suspend fun indexedForThing(thing: String): List<ArchiveRecord> = emptyList()
        override suspend fun things(): List<String> = rows.value.map { it.thing }.distinct()
    }

    /** Runs in memory, each one a map of name to bytes, with flags once released or deleted. */
    private class Space : ExportSpace {
        val runs = mutableListOf<Run>()
        var free: Long? = null

        /** Runs inside [newRun], on the staging thread. */
        var onNewRun: (() -> Unit)? = null
        override fun freeBytes(): Long? = free

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
            var released = false
            override fun release() {
                released = true
            }
            override fun delete() {
                deleted = true
            }
        }

        override fun newRun(): ExportRun = Run(runs.size).also {
            runs += it
            onNewRun?.invoke()
        }
    }

    private class Presenter(
        val closesNow: Boolean = true,
        val fail: Boolean = false,
        val waitForever: Boolean = false,
        val streams: Boolean = false,
        /** Runs on each streamed write, to fail or cancel the save part way. */
        val onWrite: (() -> Unit)? = null,
        /** Thrown after the zip was written, as a document that fails to flush or close. */
        val closeFailure: String? = null,
        /** Keeps the sheet or picker open until completed. */
        val hold: CompletableDeferred<Unit>? = null,
    ) : ExportPresenter {
        var writes = 0
        val shared = mutableListOf<Pair<List<StagedFile>, ShareKind>>()
        val saved = mutableListOf<StagedFile>()

        /** Each streamed save as its name and the bytes written to the picked document. */
        val streamed = mutableListOf<Pair<String, ByteArray>>()
        override suspend fun saveStreaming(name: String, write: suspend (ExportSink) -> Unit): Boolean? {
            if (!streams) return null
            val out = mutableListOf<Byte>()
            write(
                object : ExportSink {
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        writes++
                        onWrite?.invoke()
                        for (i in offset until offset + length) out += bytes[i]
                    }
                    override fun close() = Unit
                },
            )
            closeFailure?.let { throw IllegalStateException(it) }
            streamed += name to out.toByteArray()
            return true
        }
        override suspend fun share(files: List<StagedFile>, kind: ShareKind): Boolean {
            if (fail) error("no sheet")
            shared += files to kind
            hold?.await()
            if (waitForever) awaitCancellation()
            return closesNow
        }
        override suspend fun save(file: StagedFile): Boolean {
            if (fail) error("no picker")
            saved += file
            hold?.await()
            if (waitForever) awaitCancellation()
            return true
        }
    }

    private fun record(thing: String, name: String, bytes: ByteArray) =
        ArchiveRecord(thing = thing, name = name, bytes = bytes.size.toLong(), crc32 = 1, sha256 = "x", syncedAtEpochMs = 1_000, sourceDevice = thing)

    private class Fixture(
        scope: TestScope,
        val store: Store,
        val index: Index,
        owner: String?,
        daily: (suspend () -> List<ExportEntry>)? = null,
        holdDaily: suspend (suspend () -> Unit) -> Unit = { it() },
    ) {
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
            holdDaily,
            now = { 1_790_000_000_000L },
            io = UnconfinedTestDispatcher(scope.testScheduler),
        )
    }

    private fun TestScope.fixture(
        owner: String? = "s",
        daily: (suspend () -> List<ExportEntry>)? = null,
        holdDaily: suspend (suspend () -> Unit) -> Unit = { it() },
    ): Fixture {
        val a = record("s", nameA, bytesA)
        val b = record("s", nameB, bytesB)
        val other = record("t", nameA, bytesB)
        // A final the janitor kept but the index never verified, with bytes that are not Parquet.
        val orphan = ArchiveKey("s", orphanName)
        val store = Store(mapOf(a.key to bytesA, b.key to bytesB, other.key to bytesB, orphan to "orphan-bytes".encodeToByteArray()))
        return Fixture(this, store, Index(listOf(a, b, other)), owner, daily, holdDaily)
    }

    private val orphanName = "station=s/year=2026/month=09/day=17/data_1100_b_5-6-1.parquet"

    /** The names inside a STORED zip, read from its central directory. */
    private fun zipNames(zip: ByteArray): List<String> {
        fun u16(i: Int) = (zip[i].toInt() and 0xFF) or ((zip[i + 1].toInt() and 0xFF) shl 8)
        var eocd = zip.size - 22
        while (u16(eocd) != 0x4B50 || u16(eocd + 2) != 0x0605) eocd--
        var at = u16(eocd + 16) or (u16(eocd + 18) shl 16)
        return List(u16(eocd + 10)) {
            val nameLength = u16(at + 28)
            val name = zip.copyOfRange(at + 46, at + 46 + nameLength).decodeToString()
            at += 46 + nameLength + u16(at + 30) + u16(at + 32)
            name
        }
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
    fun aSheetWithoutACloseSignalReleasesTheRunAndTheNextExportKeepsIt() = runTest {
        val f = fixture()
        advanceUntilIdle()
        val p = Presenter(closesNow = false)
        f.controller.shareAll(p)
        advanceUntilIdle()
        assertEquals(2, p.shared.single().first.size)
        assertFalse(f.space.runs.single().deleted)
        assertTrue(f.space.runs.single().released)
        f.controller.shareAll(p)
        advanceUntilIdle()
        // A new export is no proof the first recipient finished reading (#161).
        assertFalse(f.space.runs[0].deleted)
        assertEquals(2, f.space.runs.size)
    }

    @Test
    fun aRunStagedAsTheExportIsCancelledIsRemoved() = runTest {
        val f = fixture()
        advanceUntilIdle()
        // The screen goes away while a loose copy stages. The copy has no checkpoint, so it finishes
        // and only the return from staging sees the cancel. Nothing would end that run's lease.
        f.space.onNewRun = { f.job.cancel() }
        val p = Presenter()
        f.controller.shareFile(f.controller.archived(nameA)!!, p)
        advanceUntilIdle()
        assertTrue(p.shared.isEmpty())
        assertEquals(1, f.space.runs.single().files.size)
        assertTrue(f.space.runs.single().deleted)
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
    fun aStreamingSaveWritesTheZipIntoTheDocumentAndStagesNothing() = runTest {
        val f = fixture()
        f.space.free = 10
        advanceUntilIdle()
        val p = Presenter(streams = true)
        f.controller.saveZip(p)
        advanceUntilIdle()
        val (name, zip) = p.streamed.single()
        assertTrue(name.startsWith("opensensor-space-archive-") && name.endsWith(".zip"), name)
        assertTrue(p.saved.isEmpty())
        assertTrue(f.space.runs.isEmpty())
        // Local file header first, end of central directory last, and every file of the owner stored inside.
        assertContentEquals(byteArrayOf(0x50, 0x4B, 0x03, 0x04), zip.copyOfRange(0, 4))
        assertContentEquals(byteArrayOf(0x50, 0x4B, 0x05, 0x06), zip.copyOfRange(zip.size - 22, zip.size - 18))
        assertEquals(listOf("s/$nameA", "s/$nameB"), zipNames(zip))
        val text = zip.decodeToString()
        assertTrue(bytesA.decodeToString() in text && bytesB.decodeToString() in text)
        assertFalse(f.controller.state.value.exporting)
    }

    @Test
    fun aZipThatSkipsAGoneFinalSaysHowManyAfterTheExport() = runTest {
        val f = fixture(owner = "s")
        val messages = mutableListOf<FilesMessage>()
        collectMessages(f.controller, messages)
        advanceUntilIdle()
        f.store.gone += ArchiveKey("s", nameA)
        val shared = Presenter()
        f.controller.shareZip(shared)
        advanceUntilIdle()
        assertEquals(listOf("s/$nameB"), zipNames(f.space.runs.single().files.values.single()))
        assertEquals(1, shared.shared.single().first.single().skipped)
        f.controller.saveZip(Presenter())
        advanceUntilIdle()
        val streaming = Presenter(streams = true)
        f.controller.saveZip(streaming)
        advanceUntilIdle()
        assertEquals(listOf("s/$nameB"), zipNames(streaming.streamed.single().second))
        assertEquals(List<FilesMessage>(3) { FilesMessage.Skipped(1) }, messages)
        // A zip that left nothing out says nothing.
        f.store.gone.clear()
        f.controller.shareZip(Presenter())
        f.controller.saveZip(Presenter(streams = true))
        advanceUntilIdle()
        assertEquals(3, messages.size)
    }

    @Test
    fun theSkippedCountWaitsForTheSheetOrPickerToReturn() = runTest {
        val f = fixture(owner = "s")
        val messages = mutableListOf<FilesMessage>()
        collectMessages(f.controller, messages)
        advanceUntilIdle()
        f.store.gone += ArchiveKey("s", nameA)
        val sheet = CompletableDeferred<Unit>()
        val shared = Presenter(hold = sheet)
        f.controller.shareZip(shared)
        advanceUntilIdle()
        assertEquals(1, shared.shared.size)
        assertTrue(messages.isEmpty())
        sheet.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf<FilesMessage>(FilesMessage.Skipped(1)), messages)
        val picker = CompletableDeferred<Unit>()
        val saved = Presenter(hold = picker)
        f.controller.saveZip(saved)
        advanceUntilIdle()
        assertEquals(1, saved.saved.size)
        assertEquals(1, messages.size)
        picker.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, messages.size)
    }

    @Test
    fun aDailyZipThatSkipsAGoneDayFileSaysSo() = runTest {
        val f = fixture(
            daily = {
                listOf(
                    ExportEntry("derived/v1/s/day=2026-09-16.parquet", 0) { throw ArchiveFileMissingException("evicted") },
                    ExportEntry("derived/v1/s/day=2026-09-17.parquet", 0) { ByteArrayReader(bytesA) },
                )
            },
        )
        val messages = mutableListOf<FilesMessage>()
        collectMessages(f.controller, messages)
        advanceUntilIdle()
        f.controller.shareDaily(Presenter())
        advanceUntilIdle()
        assertEquals(listOf("derived/v1/s/day=2026-09-17.parquet"), zipNames(f.space.runs.single().files.values.single()))
        assertEquals(listOf<FilesMessage>(FilesMessage.Skipped(1)), messages)
    }

    @Test
    fun aZipWithoutRoomSaysSoAndStagesNothing() = runTest {
        val f = fixture()
        f.space.free = 10
        val messages = mutableListOf<FilesMessage>()
        collectMessages(f.controller, messages)
        advanceUntilIdle()
        val p = Presenter()
        f.controller.saveZip(p)
        advanceUntilIdle()
        assertEquals(listOf<FilesMessage>(FilesMessage.NoSpace), messages)
        assertTrue(p.saved.isEmpty())
        assertTrue(f.space.runs.single().deleted)
        assertTrue(f.space.runs.single().files.isEmpty())
        assertFalse(f.controller.state.value.exporting)
    }

    @Test
    fun aFullDiskDuringAStreamingSaveSaysNoSpace() = runTest {
        val f = fixture()
        val messages = mutableListOf<FilesMessage>()
        collectMessages(f.controller, messages)
        advanceUntilIdle()
        f.controller.saveZip(Presenter(streams = true, onWrite = { throw IllegalStateException("write failed: ENOSPC (No space left on device)") }))
        advanceUntilIdle()
        f.controller.saveZip(Presenter(streams = true, closeFailure = "close failed: No space left on device"))
        advanceUntilIdle()
        assertEquals(listOf<FilesMessage>(FilesMessage.NoSpace, FilesMessage.NoSpace), messages)
        assertFalse(f.controller.state.value.exporting)
    }

    @Test
    fun aCancelledStreamingSaveStopsWriting() = runTest {
        val f = fixture()
        val messages = mutableListOf<FilesMessage>()
        collectMessages(f.controller, messages)
        advanceUntilIdle()
        lateinit var p: Presenter
        p = Presenter(streams = true, onWrite = { if (p.writes == 1) f.job.cancel() })
        f.controller.saveZip(p)
        advanceUntilIdle()
        // The local header goes out in a few small writes before the first slice checkpoint.
        assertTrue(p.writes < 20, "wrote ${p.writes} times after cancel")
        assertTrue(p.streamed.isEmpty())
        assertTrue(messages.isEmpty())
    }

    @Test
    fun aFailedExportReportsOnceAndFreesTheScreen() = runTest {
        val f = fixture()
        val messages = mutableListOf<FilesMessage>()
        collectMessages(f.controller, messages)
        advanceUntilIdle()
        f.controller.shareZip(Presenter(fail = true))
        advanceUntilIdle()
        assertEquals(listOf<FilesMessage>(FilesMessage.ExportFailed), messages)
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
        assertEquals(listOf<FilesMessage>(FilesMessage.NoDaily), messages)
        assertTrue(p.shared.isEmpty())
    }

    @Test
    fun theDailyZipIsListedAndStagedInsideTheHoldAndSharedAfterIt() = runTest {
        val steps = mutableListOf<String>()
        lateinit var f: Fixture
        val p = Presenter(closesNow = false)
        f = fixture(
            daily = {
                steps += "list"
                listOf(ExportEntry("derived/v1/s/2026-10-01.parquet", bytesA.size.toLong()) { ByteArrayReader(bytesA) })
            },
            holdDaily = { block ->
                steps += "hold"
                block()
                steps += "staged ${f.space.runs.size}, shared ${p.shared.size}"
            },
        )
        advanceUntilIdle()
        f.controller.shareDaily(p)
        advanceUntilIdle()
        assertEquals(listOf("hold", "list", "staged 1, shared 0"), steps)
        assertEquals(ShareKind.ARCHIVE, p.shared.single().second)
    }

    @Test
    fun aDailyZipStagedBeforeTheHoldFailsIsRemoved() = runTest {
        val f = fixture(
            daily = { listOf(ExportEntry("derived/v1/s/2026-10-01.parquet", bytesA.size.toLong()) { ByteArrayReader(bytesA) }) },
            // Staging returned, then the hold ends with an error, so nothing will show the zip.
            holdDaily = { block ->
                block()
                error("hold lost")
            },
        )
        advanceUntilIdle()
        val p = Presenter()
        f.controller.shareDaily(p)
        advanceUntilIdle()
        assertTrue(p.shared.isEmpty())
        assertEquals(1, f.space.runs.single().files.size)
        assertTrue(f.space.runs.single().deleted)
    }

    @Test
    fun theOwnerDecidesWhichCopyAListedNameMeans() = runTest {
        val f = fixture(owner = "t")
        advanceUntilIdle()
        assertEquals("t", f.controller.archived(nameA)?.thing)
        assertNull(f.controller.archived(nameB))
        assertEquals(listOf("t"), f.controller.state.value.shown.map { it.thing })
        f.owner.value = null
        advanceUntilIdle()
        // Without an owner a listed name never falls back to another sensor's copy.
        assertNull(f.controller.archived(nameA))
        assertEquals(3, f.controller.state.value.shown.size)
        assertFalse(f.controller.state.value.dailyAvailable)
    }

    @Test
    fun aKnownOwnerObservesOnlyItsThing() = runTest {
        val f = fixture(owner = "t")
        advanceUntilIdle()
        assertEquals(listOf<String?>("t"), f.index.observed)
        assertEquals(listOf("t"), f.controller.state.value.records.map { it.thing })
        // Another thing's new file does not reach this owner's list.
        val before = f.controller.state.value
        f.index.rows.value = f.index.rows.value + record("s", "station=s/year=2026/month=09/day=21/data_0900_b_1-2-1.parquet", bytesA)
        advanceUntilIdle()
        assertEquals(before.records, f.controller.state.value.records)
        f.owner.value = null
        advanceUntilIdle()
        assertEquals(listOf("t", null), f.index.observed)
        assertEquals(4, f.controller.state.value.records.size)
    }

    @Test
    fun sensorExportsAndCountsStayWithinItsThingButPhoneArchiveIncludesAll() = runTest {
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
        // A separate phone-archive entry has no owner and still exports every thing.
        f.owner.value = null
        advanceUntilIdle()
        assertEquals(3, f.controller.state.value.shown.size)
        val phonePresenter = Presenter()
        f.controller.shareAll(phonePresenter)
        advanceUntilIdle()
        assertEquals(3, phonePresenter.shared.single().first.size)
    }

    @Test
    fun aThingZipHoldsOnlyThatThingsVerifiedFiles() = runTest {
        val f = fixture(owner = "s")
        advanceUntilIdle()
        val p = Presenter()
        f.controller.shareZip(p)
        advanceUntilIdle()
        val zip = f.space.runs.single().files.values.single()
        // Not thing t's copy of nameA, and not the unindexed orphan of thing s.
        assertEquals(listOf("s/$nameA", "s/$nameB"), zipNames(zip))
        val streaming = Presenter(streams = true)
        f.controller.saveZip(streaming)
        advanceUntilIdle()
        assertEquals(listOf("s/$nameA", "s/$nameB"), zipNames(streaming.streamed.single().second))
    }

    @Test
    fun theWholePhoneZipHoldsEveryVerifiedFileButNoOrphan() = runTest {
        val f = fixture(owner = null)
        advanceUntilIdle()
        f.controller.shareZip(Presenter())
        advanceUntilIdle()
        assertEquals(listOf("s/$nameA", "s/$nameB", "t/$nameA"), zipNames(f.space.runs.single().files.values.single()))
    }

    @Test
    fun aThingDailyZipHoldsOnlyThatThingsDayFiles() = runTest {
        fun day(path: String) = ExportEntry(path, 0) { ByteArrayReader(bytesA) }
        val f = fixture(
            owner = "s",
            daily = {
                listOf(
                    day("derived/v1/s/day=2026-09-17.parquet"),
                    day("derived/v1/s/day=2026-09-17.manifest.json"),
                    day("derived/v1/t/day=2026-09-17.parquet"),
                    day("derived/v1/t/day=2026-09-17.manifest.json"),
                )
            },
        )
        advanceUntilIdle()
        f.controller.shareDaily(Presenter())
        advanceUntilIdle()
        assertEquals(
            listOf("derived/v1/s/day=2026-09-17.manifest.json", "derived/v1/s/day=2026-09-17.parquet"),
            zipNames(f.space.runs.single().files.values.single()),
        )
        f.owner.value = null
        advanceUntilIdle()
        f.controller.shareDaily(Presenter())
        advanceUntilIdle()
        assertEquals(4, zipNames(f.space.runs.last().files.values.single()).size)
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
