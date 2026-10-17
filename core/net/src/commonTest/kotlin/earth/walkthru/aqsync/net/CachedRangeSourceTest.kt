package earth.walkthru.aqsync.net

import earth.walkthru.aqsync.data.history.parquet.SourceChangedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CachedRangeSourceTest {
    /** Answers from [server] and records every requested range. */
    private class CountingEngine(val server: RangeServer) : HttpRangeEngine {
        val ranges = ArrayList<String?>()

        override suspend fun get(url: String, range: String?, ifRange: String?, ifNoneMatch: String?, limit: BodyLimit): HttpRangeResponse {
            ranges += range
            return server.respond(range, ifRange)
        }
    }

    private val url = "https://example.test/cells/h3_res=5/data.parquet"
    private val bytes = payload(10_000)
    private val server = RangeServer(bytes, "\"e1\"")
    private val engine = CountingEngine(server)
    private val store = MemoryBlockStore()
    private val cache = BlockCache(store, quotaBytes = { 1L shl 30 }, io = Dispatchers.Unconfined)

    private fun source(generation: String = "2026-10-08") = CachedRangeSource(HttpRangeSource(url, engine, openTailBytes = 1024), cache, url, generation, blockBytes = 1000)

    private val reads = listOf(9_000L until 10_000L, 0L until 10L, 950L until 2_050L, 4_999L..4_999L, 5L until 5L)

    private suspend fun readAll(source: CachedRangeSource): List<ByteArray> = source.readRanges(reads).map { it.toByteArray() }

    private fun expected(): List<ByteArray> = reads.map { bytes.copyOfRange(it.first.toInt(), it.last.toInt() + 1) }

    @Test
    fun `ranges inside and across blocks match the file`() = runTest {
        val got = readAll(source())
        expected().zip(got).forEach { (want, have) -> assertContentEquals(want, have) }
        assertContentEquals(bytes.copyOfRange(9_500, 10_000), source().readTail(500).toByteArray())
    }

    @Test
    fun `a second open reads every cached block with no request`() = runTest {
        val first = source()
        first.readTail(1024)
        readAll(first)
        assertTrue(engine.ranges.isNotEmpty())
        engine.ranges.clear()

        val second = source()
        assertEquals(first.id(), second.id())
        second.readTail(1024)
        expected().zip(readAll(second)).forEach { (want, have) -> assertContentEquals(want, have) }
        assertEquals(emptyList<String?>(), engine.ranges)
    }

    @Test
    fun `only missing blocks are fetched in runs`() = runTest {
        source().readRanges(listOf(0L until 1_000L))
        engine.ranges.clear()
        source().readRanges(listOf(0L until 3_000L))
        // The id comes from the meta file, the tail read pins the ETag, then one run of blocks 1 and 2.
        assertEquals(listOf<String?>("bytes=-1024", "bytes=1000-2999"), engine.ranges)
    }

    @Test
    fun `a new generation is a new key`() = runTest {
        source("a").readRanges(listOf(0L until 1_000L))
        engine.ranges.clear()
        source("b").readRanges(listOf(0L until 1_000L))
        assertTrue("bytes=0-999" in engine.ranges)
    }

    @Test
    fun `a replaced object drops the key and throws so the reader starts again`() = runTest {
        val first = source()
        first.readRanges(listOf(0L until 1_000L))
        val old = first.id()
        val replaced = payload(12_000).also { it.reverse() }
        server.bytes = replaced
        server.etag = "\"e2\""

        val second = source()
        assertEquals(old, second.id())
        val error = assertFailsWith<SourceChangedException> { second.readRanges(listOf(2_000L until 3_000L)) }
        assertEquals(old, error.previous)
        assertTrue(store.list().isEmpty())

        // The retry sees the new object and never mixes in a block of the old one.
        assertEquals(12_000, second.id().size)
        assertContentEquals(replaced.copyOfRange(0, 3_000), second.readRanges(listOf(0L until 3_000L)).single().toByteArray())
        assertContentEquals(replaced.copyOfRange(0, 3_000), source().readRanges(listOf(0L until 3_000L)).single().toByteArray())
    }

    @Test
    fun `a cached block of the wrong length is fetched again`() = runTest {
        source().readRanges(listOf(0L until 1_000L))
        val name = store.list().map { it.name }.first { !it.endsWith(".meta") }
        store.write(name, ByteArray(3))
        engine.ranges.clear()
        assertContentEquals(bytes.copyOfRange(0, 1_000), source().readRanges(listOf(0L until 1_000L)).single().toByteArray())
        assertTrue("bytes=0-999" in engine.ranges)
    }

    @Test
    fun `an object without a strong ETag bypasses the cache`() = runTest {
        server.etag = "W/\"weak\""
        val source = source()
        assertFalse(source.id().cacheable)
        assertContentEquals(bytes.copyOfRange(0, 10), source.readRanges(listOf(0L until 10L)).single().toByteArray())
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `a range outside the file throws`() = runTest {
        assertFailsWith<IndexOutOfBoundsException> { source().readRanges(listOf(9_990L until 10_001L)) }
    }
}
