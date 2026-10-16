package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.archive.ArchiveIndex
import earth.walkthru.aqsync.archive.ArchiveKey
import earth.walkthru.aqsync.archive.ArchiveRecord
import earth.walkthru.aqsync.archive.ArchiveStore
import earth.walkthru.aqsync.archive.PartialMeta
import earth.walkthru.aqsync.archive.PartialWriter
import earth.walkthru.aqsync.data.history.parquet.ByteArrayReader
import earth.walkthru.aqsync.data.history.parquet.ByteReader
import earth.walkthru.aqsync.data.history.parquet.ColumnValues
import earth.walkthru.aqsync.data.history.parquet.ParquetFile
import earth.walkthru.aqsync.data.history.parquet.ParquetLogicalType.TimeUnit
import earth.walkthru.aqsync.data.history.parquet.ParquetType
import earth.walkthru.aqsync.data.history.parquet.ParquetWriter
import earth.walkthru.aqsync.data.sync.WriterLeases
import earth.walkthru.aqsync.storage.StorageEntry
import earth.walkthru.aqsync.storage.StorageRole
import earth.walkthru.aqsync.storage.StorageRoots
import earth.walkthru.aqsync.storage.StorageTree
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
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
 * always stay. The record goes before the file, and a failed delete puts it back.
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
        override suspend fun unindexed(limit: Int): List<ArchiveRecord> = emptyList()
        override suspend fun timeline(station: String, fromNs: Long, toNs: Long): List<ArchiveRecord> = emptyList()
        override suspend fun indexedForStation(station: String): List<ArchiveRecord> = rows.value.filter { it.station == station && it.indexed }
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

    private class Setup(val store: Store, val index: Index, val tree: Tree, val cleanup: LegacyFileCleanup, val rebuilt: MutableList<Map<String, Set<Long>>>)

    private fun record(name: String, bytes: ByteArray, firstNs: Long?, lastNs: Long? = firstNs, owner: String = station) = ArchiveRecord(
        station = owner, name = name, bytes = bytes.size.toLong(), crc32 = 1, sha256 = "s", syncedAtEpochMs = 1, sourceDevice = "fake",
        indexedAtEpochMs = 1, utcRows = if (firstNs == null) 0 else 1, firstUtcNs = firstNs, lastUtcNs = lastNs,
    )

    private fun name(day: Long, n: Int) = "station=$station/year=2024/month=10/day=$day/file-$n.parquet"

    private fun setup(syncing: () -> Boolean = { false }, vararg files: Pair<ArchiveRecord, ByteArray>): Setup {
        val store = Store()
        val index = Index()
        for ((r, b) in files) {
            store.files[r.key] = b
            index.rows.value += r
        }
        val tree = Tree(store, root)
        val rebuilt = mutableListOf<Map<String, Set<Long>>>()
        val cleanup = LegacyFileCleanup(store, index, roots, tree, WriterLeases(), Dispatchers.Unconfined, syncing, afterRemoval = { rebuilt += it })
        return Setup(store, index, tree, cleanup, rebuilt)
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
    }

    @Test
    fun aFailedDeletePutsTheRecordBack() = runTest {
        val a = record(name(1, 1), nanos, day * dayNs)
        val b = record(name(1, 2), nanos, day * dayNs)
        val s = setup(files = arrayOf(a to nanos, b to nanos))
        s.tree.failOn = { it.endsWith(a.name) }
        val report = s.cleanup.remove(s.cleanup.plan())
        assertEquals(1, report.removed)
        assertEquals(1, report.kept)
        assertEquals(a, s.index.get(a.key))
        assertTrue(a.key in s.store.files)
        assertNull(s.index.get(b.key))
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
    fun removalIsRefusedWhileASyncRunsAndStopsWhenOneStarts() = runTest {
        val a = record(name(1, 1), nanos, day * dayNs)
        val b = record(name(1, 2), nanos, day * dayNs)
        val s = setup(syncing = { true }, files = arrayOf(a to nanos, b to nanos))
        val plan = s.cleanup.plan()
        assertEquals(LegacyFileCleanup.Report(kept = 2, stoppedBySync = true), s.cleanup.remove(plan))
        assertEquals(2, s.store.files.size)

        // A sync that starts after the first file stops the rest.
        var checks = 0
        val stopping = setup(syncing = { checks++ >= 2 }, files = arrayOf(a to nanos, b to nanos))
        val report = stopping.cleanup.remove(stopping.cleanup.plan())
        assertEquals(LegacyFileCleanup.Report(removed = 1, bytes = a.bytes, kept = 1, stoppedBySync = true), report)
        assertNotNull(stopping.index.get(b.key))
    }

    @Test
    fun aPathOutsideTheDurableArchiveIsNeverDeleted() = runTest {
        val a = record(name(1, 1), nanos, day * dayNs)
        val store = Store().apply { files[a.key] = nanos }
        val index = Index().apply { rows.value = listOf(a) }
        val tree = Tree(store, root)
        val noArchive = StorageRoots(mapOf(StorageRole.Derived to "/data/app/cache/derived/v1"))
        val cleanup = LegacyFileCleanup(store, index, noArchive, tree, WriterLeases(), Dispatchers.Unconfined, { false })
        assertEquals(LegacyFileCleanup.Report(kept = 1), cleanup.remove(cleanup.plan()))
        assertNotNull(index.get(a.key))
        assertTrue(tree.deleted.isEmpty())
    }
}
