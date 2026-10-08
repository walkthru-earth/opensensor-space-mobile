package earth.walkthru.aqsync.archive

import earth.walkthru.aqsync.data.protocol.ParquetVerify
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What every [ArchiveStore] and [ArchiveIndex] must do, written once. Runs here against the in-memory
 * stores. Real adapters subclass it when they move into shared code (Room KMP and the iOS store, wave 3).
 */
abstract class ArchiveStoreContract {
    abstract fun newStore(): ArchiveStore
    abstract fun newIndex(): ArchiveIndex

    private val station = "53315f5f-cb85-4d8d-b623-d56266084189"
    private val key = ArchiveKey(station, "station=$station/year=2026/month=09/day=17/data_0930_a_2-106-2.parquet")
    private val bytes = ParquetVerify.MAGIC + ByteArray(40) { it.toByte() } + ParquetVerify.MAGIC
    private val meta = PartialMeta(bytes.size.toLong(), ParquetVerify.crc32(bytes))

    @Test
    fun `a partial resumes against the same meta and restarts against another`() = runTest {
        val store = newStore()
        store.openPartial(key, meta).use { w ->
            assertEquals(0, w.resumeFrom)
            w.writeAt(0, bytes.copyOfRange(0, 10))
        }
        assertEquals(mapOf(key.name to 10L), store.partialLengths(station))
        store.openPartial(key, meta).use { w -> assertEquals(10, w.resumeFrom) }
        store.openPartial(key, meta.copy(crc32 = meta.crc32 xor 1)).use { w ->
            assertEquals(0, w.resumeFrom)
            assertEquals(0, w.length())
        }
    }

    @Test
    fun `a partial longer than the promised size restarts`() = runTest {
        val store = newStore()
        store.openPartial(key, meta).use { w -> w.writeAt(0, bytes + bytes) }
        store.openPartial(key, meta).use { w -> assertEquals(0, w.resumeFrom) }
    }

    @Test
    fun `commit moves a verified partial into the archive and never replaces`() = runTest {
        val store = newStore()
        store.openPartial(key, meta).use { w -> w.writeAt(0, bytes) }
        assertEquals(ParquetVerify.Result.Ok, ArchiveVerifier.verify(store.reader(key, partial = true), meta))
        assertFalse(store.exists(key))
        store.commit(key)
        assertTrue(store.exists(key))
        assertTrue(store.partialLengths(station).isEmpty())
        assertEquals(ParquetVerify.Result.Ok, ArchiveVerifier.verify(store.reader(key), meta))
        assertEquals(listOf(key), store.listFinal(station))
        assertEquals(listOf(key), store.listFinal())
        assertTrue(store.listFinal("other").isEmpty())
        store.openPartial(key, meta).use { w -> w.writeAt(0, bytes) }
        assertTrue(runCatching { store.commit(key) }.isFailure)
        assertTrue(bytes.contentEquals(store.reader(key).read(0, bytes.size)))
    }

    @Test
    fun `discard removes a partial and list never shows it`() = runTest {
        val store = newStore()
        store.openPartial(key, meta).use { w -> w.writeAt(0, bytes) }
        assertTrue(store.listFinal().isEmpty())
        store.discardPartial(key)
        store.discardPartial(key)
        assertTrue(store.partialLengths(station).isEmpty())
    }

    @Test
    fun `forgetting a file removes its record and its bytes together`() = runTest {
        val store = newStore()
        val index = newIndex()
        store.openPartial(key, meta).use { w -> w.writeAt(0, bytes) }
        store.commit(key)
        index.put(ArchiveRecord(station, key.name, meta.size, meta.crc32, "sha", 1, "fake"))
        assertTrue(forgetArchived(store, index, key))
        assertNull(index.get(key))
        assertFalse(store.exists(key))
        assertFalse(forgetArchived(store, index, key))
    }

    @Test
    fun `the index replaces on station and name and orders unindexed rows first`() = runTest {
        val index = newIndex()
        val a = ArchiveRecord(station, "a.parquet", 1, 1, "s", 1, "d")
        index.put(a)
        index.put(a.copy(bytes = 2))
        assertEquals(2, index.get(a.key)?.bytes)
        index.put(ArchiveRecord(station, "b.parquet", 1, 1, "s", 1, "d", indexedAtEpochMs = 5, indexError = "bad"))
        index.put(ArchiveRecord(station, "c.parquet", 1, 1, "s", 1, "d", indexedAtEpochMs = 5))
        assertEquals(listOf("a.parquet", "b.parquet"), index.unindexed(10).map { it.name })
        assertEquals(listOf("c.parquet"), index.indexedForStation(station).map { it.name })
        assertEquals(listOf(station), index.stations())
        index.remove(a.key)
        assertNull(index.get(a.key))
    }
}

class InMemoryArchiveStoreTest : ArchiveStoreContract() {
    override fun newStore(): ArchiveStore = InMemoryArchiveStore()
    override fun newIndex(): ArchiveIndex = InMemoryArchiveIndex()
}
