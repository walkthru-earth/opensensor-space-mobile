package earth.walkthru.aqsync

import earth.walkthru.aqsync.archive.ArchiveIndex
import earth.walkthru.aqsync.archive.ArchiveKey
import earth.walkthru.aqsync.archive.ArchiveRecord
import earth.walkthru.aqsync.archive.ArchiveStore
import earth.walkthru.aqsync.archive.PartialMeta
import earth.walkthru.aqsync.archive.PartialWriter
import earth.walkthru.aqsync.data.history.ArchiveIndexRepair
import earth.walkthru.aqsync.data.history.HistoryOrchestrator
import earth.walkthru.aqsync.data.history.HistoryStartup
import earth.walkthru.aqsync.data.history.parquet.ByteReader
import earth.walkthru.aqsync.net.BlockCache
import earth.walkthru.aqsync.net.MemoryBlockStore
import earth.walkthru.aqsync.storage.StorageEntry
import earth.walkthru.aqsync.storage.StorageJanitor
import earth.walkthru.aqsync.storage.StorageRoots
import earth.walkthru.aqsync.storage.StorageTree
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Android process start hands History to the shared [HistoryStartup] (#125), whose own tests
 * cover the janitor, the feed block quota and the backfill. This checks only that the app calls it.
 */
class AppStartupTest {
    private object EmptyStore : ArchiveStore {
        override suspend fun partialLengths(station: String): Map<String, Long> = emptyMap()
        override suspend fun openPartial(key: ArchiveKey, meta: PartialMeta): PartialWriter = error("read only")
        override suspend fun discardPartial(key: ArchiveKey) = Unit
        override suspend fun exists(key: ArchiveKey): Boolean = false
        override fun reader(key: ArchiveKey, partial: Boolean): ByteReader = error("no files")
        override suspend fun commit(key: ArchiveKey) = Unit
        override suspend fun listFinal(station: String?): List<ArchiveKey> = emptyList()
    }

    private object EmptyIndex : ArchiveIndex {
        override fun observeAll(): Flow<List<ArchiveRecord>> = flowOf(emptyList())
        override fun observeStation(station: String): Flow<List<ArchiveRecord>> = flowOf(emptyList())
        override suspend fun get(key: ArchiveKey): ArchiveRecord? = null
        override suspend fun put(record: ArchiveRecord) = Unit
        override suspend fun update(key: ArchiveKey, transform: (ArchiveRecord) -> ArchiveRecord) = Unit
        override suspend fun remove(key: ArchiveKey) = Unit
        override suspend fun unindexed(limit: Int): List<ArchiveRecord> = emptyList()
        override suspend fun timeline(station: String, fromNs: Long, toNs: Long): List<ArchiveRecord> = emptyList()
        override suspend fun indexedForStation(station: String): List<ArchiveRecord> = emptyList()
        override suspend fun stations(): List<String> = emptyList()
    }

    private object NoTree : StorageTree {
        override fun list(dir: String): List<StorageEntry> = emptyList()
        override fun delete(path: String) = Unit
    }

    /** Marks the Hilt base class injected, so onCreate runs without a component. */
    private fun AqSyncApplication.skipHiltInject() {
        var type: Class<*>? = javaClass
        while (type != null) {
            type.declaredFields.firstOrNull { it.name == "injected" && it.type == Boolean::class.javaPrimitiveType }?.let {
                it.isAccessible = true
                it.setBoolean(this, true)
            }
            type = type.superclass
        }
    }

    @Test
    fun processStartRunsTheSharedHistoryStart() {
        val startup = HistoryStartup(
            HistoryOrchestrator(ArchiveIndexRepair(EmptyStore, EmptyIndex, io = Dispatchers.Unconfined), EmptyIndex),
            StorageJanitor(StorageRoots(emptyMap()), NoTree, EmptyStore, EmptyIndex, Dispatchers.Unconfined),
            BlockCache(MemoryBlockStore(), { 0L }, Dispatchers.Unconfined),
        )
        val app = AqSyncApplication()
        app.skipHiltInject()
        app.historyStartup = startup
        assertFalse(startup.started)

        // The steps after the History start touch Android framework parts the JVM stub lacks.
        runCatching { app.onCreate() }

        assertTrue("onCreate starts the shared History start", startup.started)
        startup.start(CoroutineScope(Dispatchers.Unconfined)) {}.cancel()
    }
}
