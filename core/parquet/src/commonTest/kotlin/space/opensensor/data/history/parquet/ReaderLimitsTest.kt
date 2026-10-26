package space.opensensor.data.history.parquet

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Sizes a file declares are checked before they size an allocation or a read: row counts, page sizes,
 * the head magic a tail read misses, and a source that keeps changing. Each case edits the footer of
 * the page index fixture through a [FooterCache], so the bytes stay the real PyArrow file.
 */
class ReaderLimitsTest {

    private val path = "parquet/discover/cells-24h-res7-pageindex.parquet"
    private val bytes by lazy { fixtureBytes(path) }
    private val md by lazy { ParquetFile.wrap(bytes, path).metadata }

    /** A tail that misses the head of the 31 KiB fixture, and no whole-file read. */
    private val remote = RangePolicy(4096, 8L shl 20, parallelism = 1, wholeFileBelowBytes = 0, tailPrefetchBytes = 8192)

    private suspend fun openWith(metadata: ParquetFileMetadata): ParquetReader {
        val cache = FooterCache()
        val source = MemoryRangeSource(bytes, path)
        cache.put(source.id(), metadata)
        return ParquetReader.open(source, footerCache = cache)
    }

    private fun withChunk(column: String, edit: (ColumnChunkMeta) -> ColumnChunkMeta): ParquetFileMetadata {
        val index = md.leaves.first { it.name == column }.index
        return md.copy(rowGroups = md.rowGroups.map { rg -> rg.copy(columns = rg.columns.mapIndexed { i, c -> if (i == index) edit(c) else c }) })
    }

    @Test
    fun `a row count above the cap fails before any slot is allocated`() = runTest {
        val rows = ParquetLimits.MAX_ROWS + 1
        val huge = md.copy(numRows = rows, rowGroups = md.rowGroups.map { it.copy(numRows = rows) })
        val e = assertFailsWith<ParquetFormatException> { openWith(huge).use { it.scan(ScanRequest(listOf("h3_index"))) } }
        assertTrue(e.message, e.message!!.contains("exceed the limit"))
    }

    @Test
    fun `a page larger than its chunk declares fails before its buffer is allocated`() = runTest {
        val small = withChunk("h3_index") { it.copy(totalUncompressedSize = 10) }
        val e = assertFailsWith<ParquetFormatException> { openWith(small).use { it.scan(ScanRequest(listOf("h3_index"))) } }
        assertTrue(e.message, e.message!!.contains("more than the 10"))
        // The untouched columns still read.
        assertEquals(190, openWith(small).use { it.scan(ScanRequest(listOf("devices"))) }.rowCount)
    }

    @Test
    fun `an offset index that leaves its chunk fails instead of reading outside it`() = runTest {
        val short = withChunk("h3_index") { it.copy(totalCompressedSize = 64) }
        val h3 = (ParquetFile.wrap(bytes, path).readColumn("h3_index") as ColumnValues.Int64).values
        // A range inside the row group, so the scan needs the page index.
        val request = ScanRequest(listOf("h3_index"), filterColumn = "h3_index", filterRanges = listOf(h3[45]..h3[50]))
        val e = assertFailsWith<ParquetFormatException> { openWith(short).use { it.scan(request) } }
        assertTrue(e.message, e.message!!.contains("leaves its chunk"))
    }

    @Test
    fun `a footer cache keeps nothing for an id that cannot tell versions apart`() = runTest {
        val cache = FooterCache()
        val id = SourceId("https://example.org/a.parquet", bytes.size.toLong(), "W/\"weak\"", cacheable = false)
        cache.put(id, md)
        assertEquals(0, cache.size)
        assertNull(cache.get(id))
        cache.put(id.copy(cacheable = true), md)
        assertEquals(1, cache.size)
    }

    @Test
    fun `the head magic is checked on a scan near the head when the tail misses it`() = runTest {
        val good = ParquetReader.open(MemoryRangeSource(bytes, path), policy = remote).use { it.scan(ScanRequest(listOf("h3_index"))) }
        assertEquals(190, good.rowCount)

        val bad = bytes.copyOf().also { it[0] = 'X'.code.toByte() }
        val reader = ParquetReader.open(MemoryRangeSource(bad, path), policy = remote)
        val e = assertFailsWith<ParquetFormatException> { reader.use { it.scan(ScanRequest(listOf("h3_index"))) } }
        assertTrue(e.message, e.message!!.contains("PAR1"))
    }

    @Test
    fun `a source that changes again after the retry fails as a format error`() = runTest {
        val changing = object : RangeSource {
            val inner = MemoryRangeSource(bytes, path)
            var tails = 0
            override suspend fun id() = inner.id()
            override suspend fun readTail(n: Int): ByteView {
                tails++
                return inner.readTail(n)
            }
            override suspend fun readRanges(ranges: List<LongRange>): List<ByteView> = throw SourceChangedException(inner.id(), "changed")
        }
        val reader = ParquetReader.open(changing, policy = remote)
        val e = assertFailsWith<ParquetFormatException> { reader.use { it.scan(ScanRequest(listOf("h3_index"))) } }
        assertTrue(e.message, e.message!!.contains("changed again"))
        assertEquals("one reopen", 2, changing.tails)

        val neverOpens = object : RangeSource {
            val inner = MemoryRangeSource(bytes, path)
            override suspend fun id() = inner.id()
            override suspend fun readTail(n: Int): ByteView = throw SourceChangedException(inner.id(), "changed")
            override suspend fun readRanges(ranges: List<LongRange>): List<ByteView> = inner.readRanges(ranges)
        }
        assertFailsWith<ParquetFormatException> { ParquetReader.open(neverOpens, policy = remote) }
    }
}
