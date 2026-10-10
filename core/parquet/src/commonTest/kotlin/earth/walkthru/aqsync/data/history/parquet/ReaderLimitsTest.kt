package earth.walkthru.aqsync.data.history.parquet

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
}
