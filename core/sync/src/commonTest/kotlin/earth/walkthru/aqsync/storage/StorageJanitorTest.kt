package earth.walkthru.aqsync.storage

import earth.walkthru.aqsync.archive.ArchiveKey
import earth.walkthru.aqsync.archive.ArchiveLayout
import earth.walkthru.aqsync.archive.ArchiveRecord
import earth.walkthru.aqsync.data.protocol.ParquetVerify
import earth.walkthru.aqsync.data.protocol.sha256Hex
import earth.walkthru.aqsync.data.sync.MemoryArchiveIndex
import earth.walkthru.aqsync.data.sync.MemoryArchiveStore
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

        fun file(path: String, modified: Long) {
            entries[path] = false to modified
        }

        fun dir(path: String, modified: Long) {
            entries[path] = true to modified
        }

        override fun list(dir: String): List<StorageEntry> = entries.filterKeys { it.startsWith("$dir/") }.map { (path, e) ->
            StorageEntry(path.removePrefix("$dir/"), e.first, 0L, e.second)
        }

        override fun delete(path: String) {
            deleted += path
            entries.keys.removeAll { it == path || it.startsWith("$path/") }
        }
    }

    private val parquet = ParquetVerify.MAGIC + byteArrayOf(1, 2, 3) + ParquetVerify.MAGIC

    private fun janitor(tree: FakeTree, store: MemoryArchiveStore, index: MemoryArchiveIndex, scheduler: kotlinx.coroutines.test.TestCoroutineScheduler) =
        StorageJanitor(
            roots = roots,
            tree = tree,
            store = store,
            index = index,
            io = StandardTestDispatcher(scheduler),
            knownStations = { listOf(known) },
            clock = { now },
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
        tree.file(oldTemp, now - 2 * 60_000L)
        tree.file(liveTemp, now - 1_000L)
        tree.file(legacyDerived, now - hour)
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
}
