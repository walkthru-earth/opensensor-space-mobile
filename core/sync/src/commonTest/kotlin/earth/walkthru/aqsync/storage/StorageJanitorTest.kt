package earth.walkthru.aqsync.storage

import earth.walkthru.aqsync.archive.ArchiveKey
import earth.walkthru.aqsync.archive.ArchiveLayout
import earth.walkthru.aqsync.archive.ArchiveRecord
import earth.walkthru.aqsync.archive.ExportRuns
import earth.walkthru.aqsync.archive.PrunedFile
import earth.walkthru.aqsync.archive.PrunedFiles
import earth.walkthru.aqsync.data.protocol.ParquetVerify
import earth.walkthru.aqsync.data.protocol.sha256Hex
import earth.walkthru.aqsync.data.sync.DeviceCoordinator
import earth.walkthru.aqsync.data.sync.MemoryArchiveIndex
import earth.walkthru.aqsync.data.sync.MemoryArchiveStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StorageJanitorTest {
    private val now = 100L * 24 * 60 * 60_000L
    private val base = "/data/app"
    private val roots = StorageRoots(
        mapOf(
            StorageRole.Archive to "$base/archive",
            StorageRole.Partials to "$base/archive-partial",
            StorageRole.ClockAnchors to "$base/clock-anchors",
            StorageRole.Derived to "$base/derived/v1",
            StorageRole.LegacyDerived to "$base/files/derived/v1",
            StorageRole.Temp to "$base/storage-tmp",
            StorageRole.Exports to "$base/cache/export",
        ),
    )
    private val known = "11111111-2222-3333-4444-555555555555"
    private val stranger = "99999999-8888-7777-6666-555555555555"

    private class FakeTree : StorageTree {
        /** Absolute path to (isDirectory, modified time). */
        val entries = LinkedHashMap<String, Pair<Boolean, Long>>()
        val deleted = mutableListOf<String>()

        /** File sizes, for the quota step. A file not listed here has 0 bytes. */
        val sizes = HashMap<String, Long>()

        fun file(path: String, modified: Long, bytes: Long = 0L) {
            entries[path] = false to modified
            sizes[path] = bytes
        }

        fun dir(path: String, modified: Long) {
            entries[path] = true to modified
        }

        /** Runs on each listing of a directory, after the listing is taken. */
        var onList: (String) -> Unit = {}

        override fun list(dir: String): List<StorageEntry> = entries.filterKeys { it.startsWith("$dir/") }.map { (path, e) ->
            StorageEntry(path.removePrefix("$dir/"), e.first, sizes[path] ?: 0L, e.second)
        }.also { onList(dir) }

        override fun delete(path: String) {
            deleted += path
            entries.keys.removeAll { it == path || it.startsWith("$path/") }
        }
    }

    private val parquet = ParquetVerify.MAGIC + byteArrayOf(1, 2, 3) + ParquetVerify.MAGIC

    private fun janitor(
        tree: FakeTree,
        store: MemoryArchiveStore,
        index: MemoryArchiveIndex,
        scheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
        coordinator: DeviceCoordinator? = null,
        quota: DerivedQuota? = null,
        pruned: PrunedFiles = PrunedFiles.None,
    ) =
        StorageJanitor(
            roots = roots,
            tree = tree,
            store = store,
            index = index,
            io = StandardTestDispatcher(scheduler),
            knownStations = { listOf(known) },
            clock = { now },
            coordinator = coordinator ?: DeviceCoordinator(pruned),
            quota = quota,
        )

    @Test
    fun anOrphanFinalIsIndexedAndScratchIsSwept() = runTest {
        val tree = FakeTree()
        val store = MemoryArchiveStore()
        val index = MemoryArchiveIndex()
        val orphan = ArchiveKey(known, "year=2026/month=10/day=01/data.parquet")
        val broken = ArchiveKey(known, "year=2026/month=10/day=02/data.parquet")
        store.files[ArchiveLayout.finalPath(orphan)] = parquet
        store.files[ArchiveLayout.finalPath(broken)] = byteArrayOf(1, 2, 3)

        val hour = 60L * 60_000L
        val oldTemp = "$base/storage-tmp/.aqtmp-old.tmp"
        val liveTemp = "$base/storage-tmp/.aqtmp-live.tmp"
        val legacyDerived = "$base/derived/v1/$known/.derived-123.parquet"
        val dayFile = "$base/derived/v1/$known/2026-10-01.parquet"
        val oldRun = "$base/cache/export/run-old"
        val freshRun = "$base/cache/export/run-fresh"
        val strangerPartial = "$base/archive-partial/$stranger/a.parquet.partial"
        val stalePartial = "$base/archive-partial/$known/old.parquet.partial"
        val freshPartial = "$base/archive-partial/$known/fresh.parquet.partial"
        val freshMeta = "$base/archive-partial/$known/fresh.parquet.partial.meta"
        val archived = "$base/archive/$known/year=2026/month=10/day=01/data.parquet"
        val clockTemp = "$base/clock-anchors/$known/.clock-1.tmp"
        tree.file(oldTemp, now - 2 * hour)
        tree.file(liveTemp, now - 10 * 60_000L)
        tree.file(legacyDerived, now - 2 * hour)
        tree.file(dayFile, now - hour)
        tree.dir(oldRun, now - 2 * hour)
        tree.file("$oldRun/a.zip", now - 2 * hour)
        tree.dir(freshRun, now - 60_000L)
        tree.file(strangerPartial, now - 2 * hour)
        tree.file(stalePartial, now - StorageJanitor.STALE_PARTIAL_AGE_MS)
        tree.file(freshPartial, now - 40L * 24 * hour)
        tree.file(freshMeta, now - 2 * hour)
        tree.file(archived, now - 40L * 24 * hour)
        tree.file(clockTemp, now - 40L * 24 * hour)

        val report = janitor(tree, store, index, testScheduler).run()

        assertEquals(2, report.tempsSwept)
        assertEquals(1, report.exportsSwept)
        assertEquals(2, report.partialsSwept)
        assertEquals(1, report.partialsKept)
        assertEquals(1, report.adopted)
        assertEquals(1, report.unverifiable)
        assertEquals(0, report.refused)
        assertEquals(0, report.failures)

        val gone = setOf(oldTemp, legacyDerived, oldRun, strangerPartial, stalePartial)
        assertEquals(gone, tree.deleted.toSet())
        for (kept in listOf(liveTemp, dayFile, freshRun, freshPartial, freshMeta, archived, clockTemp)) {
            assertTrue(kept in tree.entries, "kept $kept")
        }
        // Nothing under a Durable root was even asked for.
        assertTrue(tree.deleted.none { roots.classOf(it) == StorageClass.Durable })

        val record = assertNotNull(index.get(orphan))
        assertEquals(parquet.size.toLong(), record.bytes)
        assertEquals(ParquetVerify.crc32(parquet), record.crc32)
        assertEquals(ParquetVerify.sha256Hex(parquet), record.sha256)
        assertEquals(StorageJanitor.ADOPTED_SOURCE, record.sourceDevice)
        assertNull(index.get(broken))
        assertTrue(ArchiveLayout.finalPath(broken) in store.files)
    }

    @Test
    fun aLeasedExportRunStaysPastTheAgeLimitAndAReleasedOneGoes() = runTest {
        val tree = FakeTree()
        val exports = "$base/cache/export"
        val leased = "run-161-leased"
        val released = "run-161-released"
        for (run in listOf(leased, released)) {
            tree.dir("$exports/$run", now - 2 * ExportRuns.MAX_AGE_MS)
            tree.file("$exports/$run/a.zip", now - 2 * ExportRuns.MAX_AGE_MS)
        }
        ExportRuns.lease(leased)
        val report = try {
            janitor(tree, MemoryArchiveStore(), MemoryArchiveIndex(), testScheduler).run()
        } finally {
            ExportRuns.release(leased)
        }
        assertEquals(1, report.exportsSwept)
        assertEquals(listOf("$exports/$released"), tree.deleted)
        assertTrue("$exports/$leased/a.zip" in tree.entries)
    }

    @Test
    fun anExportRunReleasedDuringTheSweepKeepsItsFreshTime() = runTest {
        val tree = FakeTree()
        val exports = "$base/cache/export"
        val run = "run-161-handover"
        tree.dir("$exports/$run", now - 2 * ExportRuns.MAX_AGE_MS)
        ExportRuns.lease(run)
        var listings = 0
        // The chooser opens right after the first listing. The release refreshes the time, then ends the lease.
        tree.onList = { dir ->
            if (dir == exports && listings++ == 0) {
                tree.dir("$exports/$run", now)
                ExportRuns.release(run)
            }
        }
        try {
            val report = janitor(tree, MemoryArchiveStore(), MemoryArchiveIndex(), testScheduler).run()
            assertEquals(0, report.exportsSwept)
            assertTrue(tree.deleted.isEmpty())
        } finally {
            ExportRuns.release(run)
        }
    }

    @Test
    fun aResumeHoldingTheLeaseKeepsItsStalePartial() = runTest {
        val tree = FakeTree()
        val key = ArchiveKey(known, "year=2026/month=10/day=03/data.parquet")
        val partial = "$base/${ArchiveLayout.partialPath(key)}"
        val meta = "$base/${ArchiveLayout.partialMetaPath(key)}"
        tree.file(partial, now - StorageJanitor.STALE_PARTIAL_AGE_MS)
        tree.file(meta, now - StorageJanitor.STALE_PARTIAL_AGE_MS)
        val coordinator = DeviceCoordinator()
        val opened = CompletableDeferred<Unit>()
        val reopen = CompletableDeferred<Unit>()
        // A resume that took the lease before the sweep, and rewrites its meta while the sweep waits.
        launch {
            coordinator.withWriter(key) {
                opened.complete(Unit)
                reopen.await()
                tree.file(meta, now)
            }
        }
        opened.await()

        val sweep = async { janitor(tree, MemoryArchiveStore(), MemoryArchiveIndex(), testScheduler, coordinator).run() }
        testScheduler.advanceUntilIdle()
        // The first listing saw a stale download, and the sweep now waits for the lease.
        assertTrue(tree.deleted.isEmpty())
        reopen.complete(Unit)
        val report = sweep.await()

        assertEquals(0, report.partialsSwept)
        assertEquals(1, report.partialsKept)
        assertTrue(partial in tree.entries)
        assertTrue(meta in tree.entries)
    }

    @Test
    fun adoptionWaitsForAHeldLeaseAndSkipsAFileDeletedMeanwhile() = runTest {
        val store = MemoryArchiveStore()
        val index = MemoryArchiveIndex()
        val key = ArchiveKey(known, "year=2026/month=10/day=04/data.parquet")
        store.files[ArchiveLayout.finalPath(key)] = parquet
        val coordinator = DeviceCoordinator()
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        // The old file cleanup holds the lease, drops the record and deletes the file while the janitor waits.
        launch {
            coordinator.withWriter(key) {
                held.complete(Unit)
                release.await()
                store.files.remove(ArchiveLayout.finalPath(key))
            }
        }
        held.await()

        val sweep = async { janitor(FakeTree(), store, index, testScheduler, coordinator).run() }
        testScheduler.advanceUntilIdle()
        assertFalse(sweep.isCompleted)
        release.complete(Unit)
        val report = sweep.await()

        assertEquals(0, report.adopted)
        assertEquals(0, report.unverifiable)
        assertEquals(0, report.failures)
        assertNull(index.get(key))
        assertEquals(0, coordinator.activeWriters())
    }

    @Test
    fun aFileLeftByACrashInTheOldFileCleanupIsAdoptedAndLosesItsRemovedMark() = runTest {
        val store = MemoryArchiveStore()
        val index = MemoryArchiveIndex()
        val key = ArchiveKey(known, "year=2026/month=10/day=01/data.parquet")
        val other = PrunedFile(known, "year=2026/month=10/day=02/data.parquet", 9, 1, 1)
        // The cleanup wrote the mark and removed the row, then the phone stopped before the delete.
        store.files[ArchiveLayout.finalPath(key)] = parquet
        val marks = MutableStateFlow(listOf(PrunedFile(known, key.name, parquet.size.toLong(), 1, 1), other))
        val pruned = object : PrunedFiles {
            override fun observeAll(): Flow<List<PrunedFile>> = marks
            override suspend fun put(file: PrunedFile) = error("not used")
            override suspend fun remove(key: ArchiveKey) {
                marks.value = marks.value.filterNot { it.key == key }
            }
            override suspend fun clear(): Int = error("not used")
        }

        val report = janitor(FakeTree(), store, index, testScheduler, pruned = pruned).run()

        assertEquals(1, report.adopted)
        assertNotNull(index.get(key))
        assertEquals(listOf(other), marks.value)
    }

    @Test
    fun anIndexedFinalIsLeftAsItIs() = runTest {
        val store = MemoryArchiveStore()
        val index = MemoryArchiveIndex()
        val key = ArchiveKey(known, "year=2026/month=10/day=01/data.parquet")
        store.files[ArchiveLayout.finalPath(key)] = parquet
        val existing = ArchiveRecord(key.station, key.name, parquet.size.toLong(), 1L, "ab", 5L, "device")
        index.put(existing)

        val report = janitor(FakeTree(), store, index, testScheduler).run()

        assertEquals(0, report.adopted)
        assertEquals(existing, index.get(key))
    }

    @Test
    fun anEngineRecordWrittenDuringAdoptionIsNotReplaced() = runTest {
        val store = MemoryArchiveStore()
        val index = MemoryArchiveIndex()
        val key = ArchiveKey(known, "year=2026/month=10/day=01/data.parquet")
        store.files[ArchiveLayout.finalPath(key)] = parquet
        val engine = ArchiveRecord(key.station, key.name, parquet.size.toLong(), 1L, "ab", 5L, "device")
        // The engine commits and records the file while the janitor parses its footer.
        val janitor = StorageJanitor(
            roots,
            FakeTree(),
            store,
            index,
            StandardTestDispatcher(testScheduler),
            indexer = { record -> index.put(engine); record },
            clock = { now },
        )

        val report = janitor.run()

        assertEquals(0, report.adopted)
        assertEquals(engine, index.get(key))
    }

    @Test
    fun aDeleteOutsideScratchIsRefused() = runTest {
        // A misconfigured export root inside the archive must not let the janitor touch it.
        val unsafe = StorageRoots(
            mapOf(
                StorageRole.Archive to "$base/archive",
                StorageRole.Exports to "$base/archive/$known",
            ),
        )
        val tree = FakeTree()
        tree.dir("$base/archive/$known/run-1", 0L)
        val janitor = StorageJanitor(unsafe, tree, MemoryArchiveStore(), MemoryArchiveIndex(), StandardTestDispatcher(testScheduler), clock = { now })

        val report = janitor.run()

        assertEquals(1, report.refused)
        assertTrue(tree.deleted.isEmpty())
        assertFalse(report.exportsSwept > 0)
    }

    /** Durable files far larger than any quota, with their absolute paths. None may ever be deleted. */
    private fun FakeTree.durable(): List<String> {
        val paths = listOf(
            "$base/archive/$known/year=2026/month=10/day=01/data.parquet",
            "$base/clock-anchors/$known/boot.parquet",
        )
        for (path in paths) file(path, 0L, bytes = 1L shl 30)
        return paths
    }

    @Test
    fun theLegacyDerivedRootIsRemovedWhole() = runTest {
        val tree = FakeTree()
        val durable = tree.durable()
        val legacy = "$base/files/derived/v1"
        tree.dir("$legacy/$known", now)
        tree.file("$legacy/$known/2026-10-01.parquet", now)
        tree.file("$legacy/$known/manifest.json", now)

        val report = janitor(tree, MemoryArchiveStore(), MemoryArchiveIndex(), testScheduler).run()

        assertEquals(1, report.legacyDerivedSwept)
        assertEquals(0, report.refused)
        assertEquals(0, report.failures)
        // One delete of the root itself, never of anything below a Durable root.
        assertEquals(listOf(legacy), tree.deleted)
        assertTrue(tree.entries.keys.none { it.startsWith("$legacy/") })
        for (path in durable) assertTrue(path in tree.entries, "kept $path")

        // A second run finds nothing left to remove.
        assertEquals(0, janitor(tree, MemoryArchiveStore(), MemoryArchiveIndex(), testScheduler).run().legacyDerivedSwept)
    }

    @Test
    fun aLegacyDerivedRootInsideTheArchiveIsRefused() = runTest {
        val unsafe = StorageRoots(
            mapOf(
                StorageRole.Archive to "$base/archive",
                StorageRole.LegacyDerived to "$base/archive/derived/v1",
            ),
        )
        val tree = FakeTree()
        tree.file("$base/archive/derived/v1/$known/2026-10-01.parquet", 0L)
        val janitor = StorageJanitor(unsafe, tree, MemoryArchiveStore(), MemoryArchiveIndex(), StandardTestDispatcher(testScheduler), clock = { now })

        val report = janitor.run()

        assertEquals(0, report.legacyDerivedSwept)
        assertEquals(1, report.refused)
        assertTrue(tree.deleted.isEmpty())
    }

    @Test
    fun theQuotaStepEvictsTheOldestDayFilesAndReportsThem() = runTest {
        val tree = FakeTree()
        val durable = tree.durable()
        val derived = "$base/derived/v1/$known"
        tree.file("$derived/2026-10-01.parquet", now - 3_000, bytes = 100)
        tree.file("$derived/2026-10-02.parquet", now - 2_000, bytes = 100)
        tree.file("$derived/2026-10-03.parquet", now - 1_000, bytes = 100)
        tree.file("$derived/manifest.json", now - 4_000, bytes = 10)
        val quota = DerivedQuota(QuotaEnforcer(roots, tree)) { 150L }

        val report = janitor(tree, MemoryArchiveStore(), MemoryArchiveIndex(), testScheduler, quota = quota).run()

        assertEquals(2, report.derivedEvicted)
        assertEquals(0, report.refused)
        assertEquals(0, report.failures)
        assertEquals(listOf("$derived/2026-10-01.parquet", "$derived/2026-10-02.parquet"), tree.deleted)
        assertTrue("$derived/2026-10-03.parquet" in tree.entries)
        assertTrue("$derived/manifest.json" in tree.entries)
        for (path in durable) assertTrue(path in tree.entries, "kept $path")
    }

    @Test
    fun withoutAQuotaTheDayFilesStay() = runTest {
        val tree = FakeTree()
        tree.file("$base/derived/v1/$known/2026-10-01.parquet", now - 1_000, bytes = 1L shl 40)

        val report = janitor(tree, MemoryArchiveStore(), MemoryArchiveIndex(), testScheduler).run()

        assertEquals(0, report.derivedEvicted)
        assertTrue(tree.deleted.isEmpty())
    }
}
