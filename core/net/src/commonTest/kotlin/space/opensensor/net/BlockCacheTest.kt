package space.opensensor.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BlockCacheTest {
    private class FailingStore(val inner: MemoryBlockStore = MemoryBlockStore()) : BlockStore by inner {
        var failWrites = false
        var failReads = false

        /** The most bytes on disk after any write. */
        var peak = 0L

        override fun read(name: String): ByteArray? {
            if (failReads) throw IllegalStateException("I/O error")
            return inner.read(name)
        }

        override fun write(name: String, bytes: ByteArray) {
            if (failWrites) throw IllegalStateException("disk full")
            inner.write(name, bytes)
            peak = maxOf(peak, inner.list().sumOf { it.bytes })
        }
    }

    private var quota = 1000L
    private val store = FailingStore()
    private val cache = BlockCache(store, quotaBytes = { quota }, io = Dispatchers.Unconfined)

    @Test
    fun `a put is read back and a missing name is a miss`() = runTest {
        cache.put("a", ByteArray(10) { it.toByte() })
        assertContentEquals(ByteArray(10) { it.toByte() }, cache.get("a"))
        assertNull(cache.get("b"))
        assertEquals(1, cache.stats().hits)
        assertEquals(1, cache.stats().misses)
        assertEquals(10, cache.bytes())
    }

    @Test
    fun `the least recently used block goes first over quota`() = runTest {
        cache.put("a", ByteArray(400))
        cache.put("b", ByteArray(400))
        cache.get("a")
        cache.put("c", ByteArray(400))
        assertNull(store.read("b"))
        assertEquals(setOf("a", "c"), store.list().map { it.name }.toSet())
        assertEquals(800, cache.bytes())
        assertEquals(1, cache.stats().evicted)
    }

    @Test
    fun `a shrinking quota is applied by enforce and a quota of 0 writes nothing`() = runTest {
        cache.put("a", ByteArray(400))
        cache.put("b", ByteArray(400))
        quota = 500
        cache.enforce()
        assertEquals(listOf("b"), store.list().map { it.name })
        quota = 0
        cache.enforce()
        cache.put("c", ByteArray(10))
        assertEquals(emptyList<StoredBlock>(), store.list())
        assertEquals(0, cache.bytes())
    }

    @Test
    fun `clear removes every file`() = runTest {
        cache.put("a", ByteArray(100))
        cache.put("b", ByteArray(100))
        cache.clear()
        assertEquals(emptyList<StoredBlock>(), store.list())
        assertNull(cache.get("a"))
    }

    @Test
    fun `remove by name and by prefix`() = runTest {
        cache.putAll(listOf("k1-0" to ByteArray(1), "k1-1" to ByteArray(1), "k2-0" to ByteArray(1)))
        cache.remove("k2-0")
        assertEquals(setOf("k1-0", "k1-1"), store.list().map { it.name }.toSet())
        cache.removePrefix("k1")
        assertEquals(emptyList<StoredBlock>(), store.list())
        assertEquals(0, cache.bytes())
    }

    @Test
    fun `a file the OS purged is a miss and its bytes leave the total`() = runTest {
        cache.put("a", ByteArray(300))
        store.delete("a")
        assertNull(cache.get("a"))
        assertEquals(0, cache.bytes())
    }

    @Test
    fun `a failed write is counted not thrown and a failed quota writes nothing`() = runTest {
        store.failWrites = true
        cache.put("a", ByteArray(10))
        assertEquals(1, cache.stats().failures)
        assertEquals(0, cache.bytes())
        store.failWrites = false
        val broken = BlockCache(store, quotaBytes = { error("no quota") }, io = Dispatchers.Unconfined)
        broken.put("b", ByteArray(10))
        assertEquals(emptyList<StoredBlock>(), store.list())
    }

    @Test
    fun `a new cache rebuilds its order from file times`() = runTest {
        cache.put("old", ByteArray(400))
        cache.put("new", ByteArray(400))
        val restarted = BlockCache(store, quotaBytes = { 1000L }, io = Dispatchers.Unconfined)
        assertEquals(800, restarted.bytes())
        restarted.put("next", ByteArray(400))
        assertEquals(setOf("new", "next"), store.list().map { it.name }.toSet())
    }

    @Test
    fun `a batch makes room before each write so the files never pass the quota`() = runTest {
        cache.put("a", ByteArray(400))
        cache.put("b", ByteArray(400))
        cache.putAll(listOf("c" to ByteArray(400), "d" to ByteArray(400), "huge" to ByteArray(2000)))
        assertEquals(800, store.peak)
        assertEquals(setOf("c", "d"), store.list().map { it.name }.toSet())
        // Rewriting a kept name does not evict it.
        cache.put("d", ByteArray(500))
        assertEquals(setOf("c", "d"), store.list().map { it.name }.toSet())
        assertEquals(900, cache.bytes())
    }

    @Test
    fun `a block that fails to read is deleted and a miss`() = runTest {
        cache.put("a", ByteArray(100))
        store.failReads = true
        assertNull(cache.get("a"))
        store.failReads = false
        assertEquals(emptyList<StoredBlock>(), store.list())
        assertEquals(0, cache.bytes())
        assertEquals(1, cache.stats().failures)
    }
}
