package space.opensensor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import space.opensensor.archive.ArchiveIndex
import space.opensensor.archive.ArchiveKey
import space.opensensor.archive.ArchiveRecord
import space.opensensor.archive.ArchiveStore
import space.opensensor.archive.PartialMeta
import space.opensensor.archive.PartialWriter
import space.opensensor.data.history.ArchiveIndexRepair
import space.opensensor.data.history.HistoryOrchestrator
import space.opensensor.data.history.HistoryStartup
import space.opensensor.data.history.parquet.ByteReader
import space.opensensor.net.BlockCache
import space.opensensor.net.MemoryBlockStore
import space.opensensor.storage.StorageEntry
import space.opensensor.storage.StorageJanitor
import space.opensensor.storage.StorageRoots
import space.opensensor.storage.StorageTree

/**
 * The Android process start hands History to the shared [HistoryStartup] (#125), whose own tests
 * cover the janitor, the feed block quota and the backfill. This checks only that the app calls it.
 */
class AppStartupTest {
    private object EmptyStore : ArchiveStore {
        override suspend fun partialLengths(thing: String): Map<String, Long> = emptyMap()
        override suspend fun openPartial(key: ArchiveKey, meta: PartialMeta): PartialWriter = error("read only")
        override suspend fun discardPartial(key: ArchiveKey) = Unit
        override suspend fun exists(key: ArchiveKey): Boolean = false
        override fun reader(key: ArchiveKey, partial: Boolean): ByteReader = error("no files")
        override suspend fun commit(key: ArchiveKey) = Unit
        override suspend fun listFinal(thing: String?): List<ArchiveKey> = emptyList()
    }

    private object EmptyIndex : ArchiveIndex {
        override fun observeAll(): Flow<List<ArchiveRecord>> = flowOf(emptyList())
        override fun observeThing(thing: String): Flow<List<ArchiveRecord>> = flowOf(emptyList())
        override suspend fun get(key: ArchiveKey): ArchiveRecord? = null
        override suspend fun put(record: ArchiveRecord) = Unit
        override suspend fun update(key: ArchiveKey, transform: (ArchiveRecord) -> ArchiveRecord) = Unit
        override suspend fun remove(key: ArchiveKey) = Unit
        override suspend fun unindexed(limit: Int): List<ArchiveRecord> = emptyList()
        override suspend fun timeline(thing: String, fromNs: Long, toNs: Long): List<ArchiveRecord> = emptyList()
        override suspend fun indexedForThing(thing: String): List<ArchiveRecord> = emptyList()
        override suspend fun things(): List<String> = emptyList()
    }

    private object NoTree : StorageTree {
        override fun list(dir: String): List<StorageEntry> = emptyList()
        override fun delete(path: String) = Unit
    }

    /** Marks the Hilt base class injected, so onCreate runs without a component. */
    private fun OpenSensorApplication.skipHiltInject() {
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
        val app = OpenSensorApplication()
        app.skipHiltInject()
        app.historyStartup = startup
        assertFalse(startup.started)

        // The steps after the History start touch Android framework parts the JVM stub lacks.
        runCatching { app.onCreate() }

        assertTrue("onCreate starts the shared History start", startup.started)
        startup.start(CoroutineScope(Dispatchers.Unconfined)) {}.cancel()
    }
}
