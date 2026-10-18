package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.archive.ArchiveIndex
import earth.walkthru.aqsync.archive.ArchiveKey
import earth.walkthru.aqsync.archive.ArchiveRecord
import earth.walkthru.aqsync.archive.ArchiveStore
import earth.walkthru.aqsync.archive.PartialMeta
import earth.walkthru.aqsync.archive.PartialWriter
import earth.walkthru.aqsync.archive.PrunedFile
import earth.walkthru.aqsync.archive.PrunedFiles
import earth.walkthru.aqsync.data.history.parquet.ByteArrayReader
import earth.walkthru.aqsync.data.history.parquet.ByteReader
import earth.walkthru.aqsync.data.history.parquet.ColumnValues
import earth.walkthru.aqsync.data.history.parquet.ParquetFile
import earth.walkthru.aqsync.data.history.parquet.ParquetLogicalType.TimeUnit
import earth.walkthru.aqsync.data.history.parquet.ParquetType
import earth.walkthru.aqsync.data.history.parquet.ParquetWriter
import earth.walkthru.aqsync.data.sync.DeviceCoordinator
import earth.walkthru.aqsync.storage.StorageEntry
import earth.walkthru.aqsync.storage.StorageRole
import earth.walkthru.aqsync.storage.StorageRoots
import earth.walkthru.aqsync.storage.StorageTree
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The phone only cleanup of old layout device files. Old means `event_time_utc_ns` NANOS or
 * unannotated with no field ids. Current MICROS files, files without a UTC column and unreadable files
 * always stay. The record goes before the file, and a failed delete puts it back. Each removed file
 * leaves a [PrunedFile], so sync skips it.
 */
class LegacyFileCleanupTest {
    private val station = "53315f5f-cb85-4d8d-b623-d56266084189"
    private val root = "/data/app/archive"
    private val roots = StorageRoots(
        mapOf(
            StorageRole.Archive to root,
            StorageRole.Derived to "/data/app/cache/derived/v1",
            StorageRole.Partials to "/data/app/archive-partial",
        ),
    )
    private val day = 20_000L
    private val dayNs = StationDays.DAY_NS

    private val unannotated = fixtureBytes("parquet/device/v1-lz4-2rows-dated.parquet")
    private val nanos = fixtureBytes("parquet/device/v3-board-2rg-105rows.parquet")
    private val micros = single(UtcColumns.EVENT_US, TimeUnit.MICROS, fieldId = 1)

    /**
     * The v3 board fixture in the current device layout. Every leaf gets its position as field id, and
     * with [renamed] the UTC columns become `*_utc_us` MICROS. Without it they stay `*_utc_ns` NANOS,
     * the transitional layout of the first firmware that wrote field ids.
     */
    private fun current(renamed: Boolean): ByteArray {
        val raw = ParquetFile.wrap(nanos)
        val columns = raw.leaves.mapIndexed { i, leaf ->
            val utc = UtcColumns.isUtcInstant(leaf)
            val name = if (renamed) UtcColumns.canonical(leaf.name) else leaf.name
            val unit = if (!utc) {
                null
            } else if (renamed) {
                TimeUnit.MICROS
            } else {
                TimeUnit.NANOS
            }
            ParquetWriter.Column(name, leaf.type, timestampUtc = unit, fieldId = i + 1)
        }
        val values = raw.leaves.map { leaf ->
            val col = raw.readColumn(leaf)
            if (renamed && UtcColumns.isUtcInstant(leaf)) UtcColumns.toMicros(col, leaf) else col
        }
        val sink = MemorySink()
        val writer = ParquetWriter(sink, columns, keyValueMetadata = raw.metadata.keyValueMetadata)
        writer.begin()
        writer.rowGroup(values)
        writer.finish()
        return sink.toByteArray()
    }

    private fun single(name: String, unit: TimeUnit?, fieldId: Int? = null): ByteArray {
        val sink = MemorySink()
        val writer = ParquetWriter(sink, listOf(ParquetWriter.Column(name, ParquetType.INT64, timestampUtc = unit, fieldId = fieldId)))
        writer.begin()
        writer.rowGroup(listOf(ColumnValues.Int64(longArrayOf(1_728_000_000_000_000L), null)))
        writer.finish()
        return sink.toByteArray()
    }

    private class Store : ArchiveStore {
        val files = HashMap<ArchiveKey, ByteArray>()
        override suspend fun partialLengths(station: String): Map<String, Long> = emptyMap()
        override suspend fun openPartial(key: ArchiveKey, meta: PartialMeta): PartialWriter = error("read only")
        override suspend fun discardPartial(key: ArchiveKey) = Unit
        override suspend fun exists(key: ArchiveKey): Boolean = key in files
        override fun reader(key: ArchiveKey, partial: Boolean): ByteReader = ByteArrayReader(files[key] ?: throw NoSuchElementException(key.name))
        override suspend fun commit(key: ArchiveKey) = Unit
        override suspend fun listFinal(station: String?): List<ArchiveKey> = files.keys.filter { station == null || it.station == station }
    }

    private class Index : ArchiveIndex {
        val rows = MutableStateFlow<List<ArchiveRecord>>(emptyList())

        /** Runs once, inside the first remove, like the janitor adopting the file in that window. */
        var onRemove: (() -> Unit)? = null

        /** Makes [putIfAbsent] throw, like a database that went away after the delete failed. */
        var failPut = false

        /** Runs inside [update] between its read and its write, where a writer without the gate could interleave. */
        var duringUpdate: (suspend () -> Unit)? = null
        override suspend fun update(key: ArchiveKey, transform: (ArchiveRecord) -> ArchiveRecord) {
            val latest = get(key)
            duringUpdate?.invoke()
            latest?.let { put(transform(it)) }
        }
        override suspend fun putIfAbsent(record: ArchiveRecord): Boolean {
            if (failPut) throw IllegalStateException("closed")
            return super.putIfAbsent(record)
        }
        override fun observeAll(): Flow<List<ArchiveRecord>> = rows.map { list -> list.sortedBy { it.name } }
        override fun observeStation(station: String): Flow<List<ArchiveRecord>> = rows.map { list -> list.filter { it.station == station } }
        override suspend fun get(key: ArchiveKey): ArchiveRecord? = rows.value.firstOrNull { it.key == key }
        override suspend fun put(record: ArchiveRecord) {
            rows.value = rows.value.filterNot { it.key == record.key } + record
        }
        override suspend fun remove(key: ArchiveKey) {
            rows.value = rows.value.filterNot { it.key == key }
            onRemove?.also { onRemove = null }?.invoke()
        }
        override suspend fun unindexed(limit: Int): List<ArchiveRecord> = rows.value.filter { it.indexedAtEpochMs == null }.take(limit)
        override suspend fun timeline(station: String, fromNs: Long, toNs: Long): List<ArchiveRecord> = emptyList()
        override suspend fun indexedForStation(station: String): List<ArchiveRecord> = rows.value.filter { it.station == station && it.isIndexed }
        override suspend fun stations(): List<String> = rows.value.map { it.station }.distinct()
    }

    /** Deletes reach the [Store] when the path is a final file, like the real archive directory. */
    private class Tree(private val store: Store, private val root: String) : StorageTree {
        val deleted = mutableListOf<String>()
        var failOn: (String) -> Boolean = { false }
        override fun list(dir: String): List<StorageEntry> = emptyList()
        override fun delete(path: String) {
            if (failOn(path)) throw IllegalStateException("busy")
            deleted += path
            store.files.keys.removeAll { "$root/${it.station}/${it.name}" == path }
        }
    }

    private class Pruned : PrunedFiles {
        val files = MutableStateFlow<List<PrunedFile>>(emptyList())
        var failPut = false
        override fun observeAll(): Flow<List<PrunedFile>> = files
        override suspend fun put(file: PrunedFile) {
            if (failPut) throw IllegalStateException("full")
            files.value = files.value.filterNot { it.key == file.key } + file
        }
        override suspend fun remove(key: ArchiveKey) {
            files.value = files.value.filterNot { it.key == key }
        }
        override suspend fun clear(): Int = files.value.size.also { files.value = emptyList() }
    }

    private class Setup(
        val store: Store,
        val index: Index,
        val tree: Tree,
        val cleanup: LegacyFileCleanup,
        val rebuilt: MutableList<Map<String, Set<Long>>>,
        val coordinator: DeviceCoordinator,
        val pruned: Pruned,
    )

    private fun record(name: String, bytes: ByteArray, firstNs: Long?, lastNs: Long? = firstNs, owner: String = station) = ArchiveRecord(
        station = owner, name = name, bytes = bytes.size.toLong(), crc32 = 1, sha256 = "s", syncedAtEpochMs = 1, sourceDevice = "fake",
        indexedAtEpochMs = 1, utcRows = if (firstNs == null) 0 else 1, firstUtcNs = firstNs, lastUtcNs = lastNs,
    )

    private fun name(day: Long, n: Int) = "station=$station/year=2024/month=10/day=$day/file-$n.parquet"

    private fun setup(
        vararg files: Pair<ArchiveRecord, ByteArray>,
        afterRemoval: (suspend (Map<String, Set<Long>>) -> Unit)? = null,
    ): Setup {
        val store = Store()
        val index = Index()
        for ((r, b) in files) {
            store.files[r.key] = b
            index.rows.value += r
        }
        val tree = Tree(store, root)
        val rebuilt = mutableListOf<Map<String, Set<Long>>>()
        val pruned = Pruned()
        val coordinator = DeviceCoordinator(pruned)
        val cleanup = LegacyFileCleanup(
            store,
            index,
            roots,
            tree,
            coordinator,
            Dispatchers.Unconfined,
            afterRemoval = afterRemoval ?: { rebuilt += it },
            nowEpochMs = { 42 },
        )
        return Setup(store, index, tree, cleanup, rebuilt, coordinator, pruned)
    }

    @Test
    fun deviceFixturesAreOldAndMicrosWithFieldIdsIsCurrent() {
        listOf(
            "v1-lz4-1row-unsynced", "v1-lz4-2rows-dated", "v1-lz4-90rows-dated", "v1-none-1row-dated", "v1-none-64rows-unsynced",
            "v1-none-90rows-dated", "v2-lz4-1row-dated", "v2-lz4-4rows-dated", "v2-none-90rows-unsynced", "v3-board-2rg-105rows", "v3-board-2rg-180rows",
        ).forEach { assertTrue(UtcColumns.isLegacyLayout(ParquetFile.wrap(fixtureBytes("parquet/device/$it.parquet")).metadata), it) }
        assertFalse(UtcColumns.isLegacyLayout(ParquetFile.wrap(micros).metadata))
    }

    @Test
    fun aFileWithoutUtcOrWithFieldIdsOrMicrosUnderTheOldNameIsKept() {
        assertFalse(UtcColumns.isLegacyLayout(ParquetFile.wrap(single("sequence", null)).metadata))
        assertFalse(UtcColumns.isLegacyLayout(ParquetFile.wrap(single(UtcColumns.EVENT_NS, TimeUnit.NANOS, fieldId = 1)).metadata))
        assertFalse(UtcColumns.isLegacyLayout(ParquetFile.wrap(single(UtcColumns.EVENT_NS, TimeUnit.MICROS)).metadata))
        assertFalse(UtcColumns.isLegacyLayout(ParquetFile.wrap(single(UtcColumns.EVENT_US, TimeUnit.MICROS)).metadata))
        assertTrue(UtcColumns.isLegacyLayout(ParquetFile.wrap(single(UtcColumns.EVENT_NS, TimeUnit.NANOS)).metadata))
        assertTrue(UtcColumns.isLegacyLayout(ParquetFile.wrap(single(UtcColumns.EVENT_NS, null)).metadata))
    }

    @Test
    fun theDryRunCountsOnlyOldFilesAndChangesNothing() = runTest {
        val old1 = record(name(1, 1), unannotated, day * dayNs)
        val old2 = record(name(1, 2), nanos, null)
        val current = record(name(1, 3), micros, day * dayNs)
        val noUtc = record(name(1, 4), single("sequence", null), null)
        val broken = record(name(1, 5), byteArrayOf(1, 2, 3), day * dayNs)
        val s = setup(files = arrayOf(old1 to unannotated, old2 to nanos, current to micros, noUtc to single("sequence", null), broken to byteArrayOf(1, 2, 3)))
        val plan = s.cleanup.plan()
        assertEquals(listOf(old1.key, old2.key), plan.keys)
        assertEquals(old1.bytes + old2.bytes, plan.bytes)
        assertEquals(1, plan.stations)
        assertEquals(1, plan.skipped)
        assertEquals(5, s.store.files.size)
        assertEquals(5, s.index.rows.value.size)
        assertTrue(s.tree.deleted.isEmpty())
    }

    @Test
    fun aRangeIncludesBothEndDaysAndLeavesUnplacedFilesOut() = runTest {
        val before = record(name(1, 1), nanos, day * dayNs - 1)
        val first = record(name(1, 2), nanos, day * dayNs)
        val last = record(name(1, 3), nanos, (day + 2) * dayNs - 1)
        val after = record(name(1, 4), nanos, (day + 2) * dayNs)
        val spanning = record(name(1, 5), nanos, day * dayNs - 10, (day + 5) * dayNs)
        val unplaced = record(name(1, 6), nanos, null)
        val estimated = record(name(1, 7), nanos, null).copy(estFirstUtcNs = (day + 1) * dayNs, estLastUtcNs = (day + 1) * dayNs)
        val s = setup(files = arrayOf(before, first, last, after, spanning, unplaced, estimated).map { it to nanos }.toTypedArray())
        val plan = s.cleanup.plan(UtcDayRange(day, day + 1))
        assertEquals(listOf(first.key, last.key, spanning.key, estimated.key), plan.keys)
        assertEquals(7, s.cleanup.plan(null).files)
        assertFailsWith<IllegalArgumentException> { UtcDayRange(day + 1, day) }
    }

    @Test
    fun removalDropsTheRecordThenTheFileAndRebuildsTheirDays() = runTest {
        val old = record(name(1, 1), nanos, day * dayNs, (day + 1) * dayNs)
        val unplaced = record(name(1, 2), unannotated, null, owner = "other-station")
        val current = record(name(1, 3), micros, day * dayNs)
        val s = setup(files = arrayOf(old to nanos, unplaced to unannotated, current to micros))
        val report = s.cleanup.remove(s.cleanup.plan())
        assertEquals(LegacyFileCleanup.Report(removed = 2, bytes = old.bytes + unplaced.bytes), report)
        assertEquals(listOf(current.key), s.index.rows.value.map { it.key })
        assertEquals(setOf(current.key), s.store.files.keys)
        assertEquals(listOf("$root/$station/${old.name}", "$root/other-station/${unplaced.name}"), s.tree.deleted)
        assertEquals(listOf(mapOf(station to setOf(day, day + 1), "other-station" to emptySet())), s.rebuilt)
        assertEquals(
            listOf(PrunedFile(station, old.name, old.bytes, old.crc32, 42), PrunedFile("other-station", unplaced.name, unplaced.bytes, unplaced.crc32, 42)),
            s.pruned.files.value,
        )
    }

    @Test
    fun aFailedDeletePutsTheRecordBack() = runTest {
        val a = record(name(1, 1), nanos, day * dayNs)
        val b = record(name(1, 2), nanos, day * dayNs)
        val s = setup(files = arrayOf(a to nanos, b to nanos))
        s.tree.failOn = { it.endsWith(a.name) }
        val report = s.cleanup.remove(s.cleanup.plan())
        assertEquals(1, report.removed)
        assertEquals(0, report.kept)
        assertEquals(1, report.failed)
        assertEquals(a, s.index.get(a.key))
        assertTrue(a.key in s.store.files)
        assertNull(s.index.get(b.key))
        // Only the file that went stays skipped by sync.
        assertEquals(listOf(b.key), s.pruned.files.value.map { it.key })
    }

    @Test
    fun aRemovedMarkThatCannotBeWrittenKeepsTheFile() = runTest {
        val a = record(name(1, 1), nanos, day * dayNs)
        val s = setup(files = arrayOf(a to nanos))
        s.pruned.failPut = true
        assertEquals(LegacyFileCleanup.Report(failed = 1), s.cleanup.remove(s.cleanup.plan()))
        assertEquals(a, s.index.get(a.key))
        assertTrue(a.key in s.store.files)
        assertTrue(s.tree.deleted.isEmpty())
    }

    @Test
    fun theLiveRecordIsCheckedAgainUnderTheGate() = runTest {
        val a = record(name(1, 1), nanos, day * dayNs)
        val b = record(name(1, 2), nanos, day * dayNs)
        val s = setup(files = arrayOf(a to nanos, b to nanos))
        val plan = s.cleanup.plan()
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        // Another writer holds the gate after the cleanup read the records, and changes both meanwhile.
        val writer = launch {
            s.coordinator.withStation(station) {
                held.complete(Unit)
                release.await()
                s.index.put(a.copy(crc32 = 2))
                s.index.remove(b.key)
            }
        }
        held.await()
        val cleanup = launch { assertEquals(LegacyFileCleanup.Report(kept = 2), s.cleanup.remove(plan)) }
        testScheduler.runCurrent()
        release.complete(Unit)
        writer.join()
        cleanup.join()
        assertEquals(2, s.store.files.size)
        assertTrue(s.tree.deleted.isEmpty())
        assertTrue(s.pruned.files.value.isEmpty())
    }

    @Test
    fun theCleanupWaitsForTheWriterLeaseOfARunningDownloadBeforeItWritesTheTombstone() = runTest {
        val a = record(name(1, 1), nanos, day * dayNs)
        val s = setup(files = arrayOf(a to nanos))
        val plan = s.cleanup.plan()
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        // A download of the same file holds its writer lease.
        val download = launch {
            s.coordinator.withWriter(a.key) {
                held.complete(Unit)
                release.await()
            }
        }
        held.await()
        val cleaning = launch { assertEquals(1, s.cleanup.remove(plan).removed) }
        testScheduler.runCurrent()
        // No tombstone, no dropped record and no deleted file while the download holds the lease.
        assertTrue(s.pruned.files.value.isEmpty())
        assertNotNull(s.index.get(a.key))
        assertTrue(s.tree.deleted.isEmpty())
        release.complete(Unit)
        download.join()
        cleaning.join()
        assertEquals(listOf(a.key), s.pruned.files.value.map { it.key })
        assertTrue(s.store.files.isEmpty())
    }

    @Test
    fun repairAndCleanupTakeTurnsSoNoRecordComesBackForADeletedFile() = runTest {
        val a = record(name(1, 1), nanos, day * dayNs).copy(indexedAtEpochMs = null)
        val s = setup(files = arrayOf(a to nanos))
        val plan = s.cleanup.plan()
        val repair = ArchiveIndexRepair(s.store, s.index, io = Dispatchers.Unconfined, coordinator = s.coordinator)
        val inside = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        s.index.duringUpdate = {
            inside.complete(Unit)
            release.await()
        }
        // Repair read the record and is about to write its index columns back.
        val repairing = launch { repair.backfill() }
        inside.await()
        val cleaning = launch { s.cleanup.remove(plan) }
        testScheduler.runCurrent()
        // The cleanup waits for the gate, so nothing is gone yet.
        assertNotNull(s.index.get(a.key))
        assertTrue(a.key in s.store.files)
        release.complete(Unit)
        repairing.join()
        cleaning.join()
        assertTrue(s.index.rows.value.isEmpty())
        assertTrue(s.store.files.isEmpty())
        assertEquals(listOf(a.key), s.pruned.files.value.map { it.key })
    }

    @Test
    fun aFileThatChangedOrWentAwayAfterTheDryRunIsKept() = runTest {
        val a = record(name(1, 1), nanos, day * dayNs)
        val b = record(name(1, 2), nanos, day * dayNs)
        val s = setup(files = arrayOf(a to nanos, b to nanos))
        val plan = s.cleanup.plan()
        s.store.files[a.key] = micros
        s.index.remove(b.key)
        val report = s.cleanup.remove(plan)
        assertEquals(LegacyFileCleanup.Report(removed = 0, kept = 2), report)
        assertNotNull(s.index.get(a.key))
        assertTrue(s.tree.deleted.isEmpty())
        assertTrue(s.rebuilt.isEmpty())
    }

    @Test
    fun aRecordAdoptedBetweenTheTwoStepsIsRemovedWithItsFile() = runTest {
        val a = record(name(1, 1), nanos, day * dayNs)
        val s = setup(files = arrayOf(a to nanos))
        s.index.onRemove = { s.index.rows.value += a.copy(sourceDevice = "adopted") }
        assertEquals(1, s.cleanup.remove(s.cleanup.plan()).removed)
        assertTrue(s.index.rows.value.isEmpty())
        assertTrue(s.store.files.isEmpty())
    }

    @Test
    fun aPathOutsideTheDurableArchiveIsNeverDeleted() = runTest {
        val a = record(name(1, 1), nanos, day * dayNs)
        val store = Store().apply { files[a.key] = nanos }
        val index = Index().apply { rows.value = listOf(a) }
        val tree = Tree(store, root)
        val noArchive = StorageRoots(mapOf(StorageRole.Derived to "/data/app/cache/derived/v1"))
        val cleanup = LegacyFileCleanup(store, index, noArchive, tree, DeviceCoordinator(Pruned()), Dispatchers.Unconfined)
        assertEquals(LegacyFileCleanup.Report(kept = 1), cleanup.remove(cleanup.plan()))
        assertNotNull(index.get(a.key))
        assertTrue(tree.deleted.isEmpty())
    }

    @Test
    fun realCurrentAndTransitionalFilesAreNeverOld() = runTest {
        val renamed = current(renamed = true)
        val transitional = current(renamed = false)
        val renamedMd = ParquetFile.wrap(renamed).metadata
        assertTrue(renamedMd.leaves.size > 2)
        assertEquals(TimeUnit.MICROS, renamedMd.leaf(UtcColumns.EVENT_US)?.timestampUtcUnit)
        assertEquals(TimeUnit.MICROS, renamedMd.leaf(UtcColumns.ANCHOR_US)?.timestampUtcUnit)
        assertTrue(renamedMd.leaves.all { it.fieldId != null })
        assertFalse(UtcColumns.isLegacyLayout(renamedMd))
        val transitionalMd = ParquetFile.wrap(transitional).metadata
        assertEquals(TimeUnit.NANOS, transitionalMd.leaf(UtcColumns.EVENT_NS)?.timestampUtcUnit)
        assertTrue(transitionalMd.leaves.all { it.fieldId != null })
        assertFalse(UtcColumns.isLegacyLayout(transitionalMd))

        val old = record(name(1, 1), nanos, day * dayNs)
        val now = record(name(1, 2), renamed, day * dayNs)
        val between = record(name(1, 3), transitional, day * dayNs)
        val s = setup(files = arrayOf(old to nanos, now to renamed, between to transitional))
        val plan = s.cleanup.plan()
        assertEquals(listOf(old.key), plan.keys)
        assertEquals(0, plan.skipped)
        assertEquals(1, s.cleanup.remove(plan).removed)
        assertEquals(setOf(now.key, between.key), s.store.files.keys)
    }

    @Test
    fun aRebuildThatThrowsStillReportsTheRemovedFiles() = runTest {
        val a = record(name(1, 1), nanos, day * dayNs)
        val s = setup(files = arrayOf(a to nanos), afterRemoval = { throw IllegalStateException("derive") })
        val report = s.cleanup.remove(s.cleanup.plan())
        assertEquals(LegacyFileCleanup.Report(removed = 1, bytes = a.bytes, rebuildFailed = true), report)
        assertTrue(s.store.files.isEmpty())
        assertNull(s.index.get(a.key))
    }

    @Test
    fun aFailingRestoreDoesNotEscapeAndTheRestGoesOn() = runTest {
        val a = record(name(1, 1), nanos, day * dayNs)
        val b = record(name(1, 2), nanos, day * dayNs)
        val s = setup(files = arrayOf(a to nanos, b to nanos))
        s.tree.failOn = { it.endsWith(a.name) }
        s.index.failPut = true
        val report = s.cleanup.remove(s.cleanup.plan())
        assertEquals(LegacyFileCleanup.Report(removed = 1, bytes = b.bytes, failed = 1), report)
        // The file stays without its record, the startup janitor adopts it again. Sync no longer skips it.
        assertTrue(a.key in s.store.files)
        assertNull(s.index.get(a.key))
        assertEquals(listOf(b.key), s.pruned.files.value.map { it.key })
        assertEquals(listOf(mapOf(station to setOf(day))), s.rebuilt)
    }

    @Test
    fun aCancelledRemovalFinishesItsFileAndRebuildsWhatWentAway() = runTest {
        val a = record(name(1, 1), nanos, day * dayNs)
        val b = record(name(1, 2), nanos, (day + 1) * dayNs)
        lateinit var s: Setup
        val job = launch {
            s.cleanup.remove(s.cleanup.plan())
        }
        s = setup(files = arrayOf(a to nanos, b to nanos))
        // The first delete cancels the caller, like leaving Settings while it runs.
        s.tree.failOn = { job.cancel(); false }
        job.join()
        assertTrue(job.isCancelled)
        assertNull(s.index.get(a.key))
        assertFalse(a.key in s.store.files)
        assertNotNull(s.index.get(b.key))
        assertTrue(b.key in s.store.files)
        assertEquals(listOf(mapOf(station to setOf(day))), s.rebuilt)
    }

    @Test
    fun archiveDaysSpanEveryPlacedFileAndFarDaysSaturate() = runTest {
        val a = record(name(1, 1), nanos, day * dayNs + 5)
        // The latest file starts two days before it ends, so the range ends on its last row, not its first.
        val b = record(name(1, 2), nanos, (day + 1) * dayNs, (day + 3) * dayNs - 1)
        val unplaced = record(name(1, 3), nanos, null)
        val s = setup(files = arrayOf(a, b, unplaced).map { it to nanos }.toTypedArray())
        assertEquals(UtcDayRange(day, day + 2), s.cleanup.archiveDays())
        assertNull(setup(files = arrayOf(unplaced to nanos)).cleanup.archiveDays())
        // A day past the Long range of nanoseconds saturates instead of wrapping to another date.
        val far = UtcDayRange(-1_000_000_000L, 1_000_000_000L)
        assertEquals(Long.MIN_VALUE, far.fromNs)
        assertEquals(Long.MAX_VALUE, far.untilNs)
        assertTrue(far.covers(a))
        assertEquals(Long.MAX_VALUE, UtcDayRange(0, Long.MAX_VALUE).untilNs)
        assertFalse(UtcDayRange(1_000_000_000L, 1_000_000_001L).covers(b))
        assertEquals(day * dayNs, UtcDayRange(day, day).fromNs)
    }
}
