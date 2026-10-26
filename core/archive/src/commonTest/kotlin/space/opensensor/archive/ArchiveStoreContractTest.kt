package space.opensensor.archive

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import space.opensensor.data.protocol.ParquetVerify
import space.opensensor.storage.AlreadyCommitted
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    private val thing = "53315f5f-cb85-4d8d-b623-d56266084189"
    private val key = ArchiveKey(thing, "station=$thing/year=2026/month=09/day=17/data_0930_a_2-106-2.parquet")
    private val bytes = ParquetVerify.MAGIC + ByteArray(40) { it.toByte() } + ParquetVerify.MAGIC
    private val meta = PartialMeta(bytes.size.toLong(), ParquetVerify.crc32(bytes))

    @Test
    fun `a partial resumes against the same meta and restarts against another`() = runTest {
        val store = newStore()
        store.openPartial(key, meta).use { w ->
            assertEquals(0, w.resumeFrom)
            w.writeAt(0, bytes.copyOfRange(0, 10))
        }
        assertEquals(mapOf(key.name to 10L), store.partialLengths(thing))
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
        assertTrue(store.partialLengths(thing).isEmpty())
        assertEquals(ParquetVerify.Result.Ok, ArchiveVerifier.verify(store.reader(key), meta))
        assertEquals(listOf(key), store.listFinal(thing))
        assertEquals(listOf(key), store.listFinal())
        assertTrue(store.listFinal("other").isEmpty())
        store.openPartial(key, meta).use { w -> w.writeAt(0, bytes) }
        assertFailsWith<AlreadyCommitted> { store.commit(key) }
        assertTrue(bytes.contentEquals(store.reader(key).read(0, bytes.size)))
        // The refused partial stays for the caller to discard.
        assertEquals(mapOf(key.name to bytes.size.toLong()), store.partialLengths(thing))
    }

    @Test
    fun `discard removes a partial and list never shows it`() = runTest {
        val store = newStore()
        store.openPartial(key, meta).use { w -> w.writeAt(0, bytes) }
        assertTrue(store.listFinal().isEmpty())
        store.discardPartial(key)
        store.discardPartial(key)
        assertTrue(store.partialLengths(thing).isEmpty())
    }

    @Test
    fun `the index replaces on thing and name and orders unindexed rows first`() = runTest {
        val index = newIndex()
        val a = ArchiveRecord(thing, "a.parquet", 1, 1, "s", 1, "d")
        index.put(a)
        index.put(a.copy(bytes = 2))
        assertEquals(2, index.get(a.key)?.bytes)
        index.put(ArchiveRecord(thing, "b.parquet", 1, 1, "s", 1, "d", indexedAtEpochMs = 5, indexError = "bad"))
        index.put(ArchiveRecord(thing, "c.parquet", 1, 1, "s", 1, "d", indexedAtEpochMs = 5))
        assertEquals(listOf("a.parquet", "b.parquet"), index.unindexed(10).map { it.name })
        assertEquals(listOf("c.parquet"), index.indexedForThing(thing).map { it.name })
        assertEquals(listOf(thing), index.things())
        index.remove(a.key)
        assertNull(index.get(a.key))
    }

    @Test
    fun `update transforms the latest record and skips a missing one`() = runTest {
        val index = newIndex()
        val a = ArchiveRecord(thing, "a.parquet", 1, 1, "", 1, "d")
        index.put(a)
        index.put(a.copy(sha256 = "filled"))
        index.update(a.key) { it.copy(indexedAtEpochMs = 9, rows = 3) }
        val after = index.get(a.key)
        assertEquals("filled", after?.sha256)
        assertEquals(3L, after?.rows)
        index.update(ArchiveKey(thing, "missing.parquet")) { error("no record to transform") }
        assertNull(index.get(ArchiveKey(thing, "missing.parquet")))
    }

    @Test
    fun `observers see records by name and per thing and the narrow views`() = runTest {
        val index = newIndex()
        val other = "11111111-2222-3333-4444-555555555555"
        val b = ArchiveRecord(thing, "b.parquet", 1, 1, "s", 1, "d")
        val a = ArchiveRecord(other, "a.parquet", 1, 1, "s", 1, "d", indexedAtEpochMs = 5, utcRows = 1, lastUtcNs = 20)
        val c = ArchiveRecord(thing, "c.parquet", 1, 1, "s", 1, "d", indexedAtEpochMs = 5, utcRows = 1, lastUtcNs = 10)
        val failed = ArchiveRecord(thing, "d.parquet", 1, 1, "s", 1, "d", indexedAtEpochMs = 5, indexError = "bad", utcRows = 1, lastUtcNs = 30)
        for (r in listOf(c, failed, a, b)) index.put(r)
        assertEquals(listOf("a.parquet", "b.parquet", "c.parquet", "d.parquet"), index.observeAll().first().map { it.name })
        assertEquals(listOf("b.parquet", "c.parquet", "d.parquet"), index.observeThing(thing).first().map { it.name })
        assertTrue(index.observeNeverIndexed().first())
        // A failed file has no place on the timeline, however late its rows.
        assertEquals(mapOf(thing to 10L, other to 20L), index.observeLatestTimed().first())
        index.update(b.key) { it.copy(indexedAtEpochMs = 6) }
        assertFalse(index.observeNeverIndexed().first())
        index.remove(c.key)
        assertEquals(mapOf(other to 20L), index.observeLatestTimed().first())
    }
}

class InMemoryArchiveStoreTest : ArchiveStoreContract() {
    override fun newStore(): ArchiveStore = InMemoryArchiveStore()
    override fun newIndex(): ArchiveIndex = InMemoryArchiveIndex()
}
