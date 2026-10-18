package earth.walkthru.aqsync.data.history.parquet

import earth.walkthru.aqsync.data.history.parquet.ThriftCompactReader.Type
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * One test per footer guard (#97). Each builds a footer that is valid except for one field, so the test
 * fails when its guard is removed: without the guard the footer parses, or a range wraps around.
 */
class FooterHardeningTest {

    /** The knobs of a one-column, one-row-group footer. The defaults parse. */
    private class Spec {
        var numRows: ThriftCompactWriter.() -> Unit = { i64(3, 3) }
        var groupRows = 3L
        var schemaListType = Type.STRUCT
        var numChildren = 1
        var typeLength: Int? = null
        var numValues = 3L
        var compressed = 16L
        var uncompressed = 16L
        var dataPageOffset = 4L
        var dictionaryPageOffset: Long? = null
        var offsetIndex: Pair<Long, Int>? = null
        var extra: ThriftCompactWriter.() -> Unit = {}

        /** The column chunk bytes, 16 zero bytes when null. */
        var pages: ByteArray? = null
    }

    private fun file(edit: Spec.() -> Unit = {}): ByteArray {
        val s = Spec().apply(edit)
        val footer = ThriftCompactWriter().apply {
            structBegin()
            i32(1, 2)
            listBegin(2, s.schemaListType, 2)
            listStruct {
                string(4, "schema")
                i32(5, s.numChildren)
            }
            listStruct {
                i32(1, ParquetType.INT64.code)
                s.typeLength?.let { i32(2, it) }
                i32(3, ParquetRepetition.REQUIRED.code)
                string(4, "v")
            }
            s.numRows(this)
            listBegin(4, Type.STRUCT, 1)
            listStruct {
                listBegin(1, Type.STRUCT, 1)
                listStruct {
                    i64(2, s.dataPageOffset)
                    struct(3) {
                        i32(1, ParquetType.INT64.code)
                        listBegin(2, Type.I32, 1)
                        listI32(ParquetEncoding.PLAIN.code)
                        listBegin(3, Type.BINARY, 1)
                        listString("v")
                        i32(4, ParquetCodec.UNCOMPRESSED.code)
                        i64(5, s.numValues)
                        i64(6, s.uncompressed)
                        i64(7, s.compressed)
                        i64(9, s.dataPageOffset)
                        s.dictionaryPageOffset?.let { i64(11, it) }
                    }
                    s.offsetIndex?.let { (offset, length) ->
                        i64(4, offset)
                        i32(5, length)
                    }
                }
                i64(2, 16)
                i64(3, s.groupRows)
            }
            s.extra(this)
            structEnd()
        }.toByteArray()
        return MAGIC + (s.pages ?: ByteArray(16)) + footer + le32(footer.size) + MAGIC
    }

    private fun refused(bytes: ByteArray, expected: String) {
        val e = assertFailsWith<ParquetFormatException> { ParquetFooterParser.parse(bytes) }
        assertTrue(e.message, e.message!!.contains(expected))
    }

    @Test
    fun `the base footer parses`() {
        val md = ParquetFooterParser.parse(file())
        assertEquals(3L, md.numRows)
        assertEquals("v", md.leaves.single().name)
    }

    @Test
    fun `a negative map size is refused`() {
        // An unknown MAP field, so the reader skips it. Zigzag of Int.MIN_VALUE is the varint 0xFFFFFFFF, read back as -1.
        refused(file { extra = { fieldBegin(20, Type.MAP); listI32(Int.MIN_VALUE) } }, "negative map size")
    }

    @Test
    fun `a map larger than the footer is refused before it is walked`() {
        refused(file { extra = { fieldBegin(20, Type.MAP); listI32(500_000) } }, "map of")
    }

    @Test
    fun `a field with the wrong thrift type is refused`() {
        // num_rows as an i32 still reads as the same varint, so only the type check notices.
        refused(file { numRows = { i32(3, 3) } }, "wire type")
        // The schema list tagged as a list of strings.
        refused(file { schemaListType = Type.BINARY }, "lists type")
    }

    @Test
    fun `row groups that disagree with num_rows are refused`() {
        refused(file { groupRows = 2 }, "row groups hold 2 rows, footer declares 3")
    }

    @Test
    fun `a sum of row group rows past Long MAX_VALUE is refused`() {
        assertFailsWith<ParquetFormatException> { checkedAdd(Long.MAX_VALUE, 1, "rows") }
        assertFailsWith<ParquetFormatException> { checkedAdd(Long.MIN_VALUE, -1, "rows") }
        assertEquals(Long.MAX_VALUE, checkedAdd(Long.MAX_VALUE - 1, 1, "rows"))
        assertFailsWith<ParquetFormatException> { checkedToInt(Int.MAX_VALUE + 1L, "length") }
        assertFailsWith<ParquetFormatException> { checkedToInt(-1, "length") }
        assertEquals(7, checkedToInt(7, "length"))
    }

    @Test
    fun `negative sizes and offsets in column metadata are refused`() {
        refused(file { compressed = -1 }, "negative size or offset")
        refused(file { uncompressed = -1 }, "negative size or offset")
        refused(file { numValues = -1 }, "negative size or offset")
        refused(file { dataPageOffset = -1 }, "negative size or offset")
        refused(file { dictionaryPageOffset = -1 }, "negative page offset")
        refused(file { typeLength = -1 }, "type_length")
        refused(file { numChildren = -1 }, "declares -1 children")
    }

    @Test
    fun `a page index pointer whose end overflows is dropped`() {
        val good = ParquetFooterParser.parse(file { offsetIndex = 4L to 8 })
        assertEquals(4L until 12L, good.rowGroups[0].columns[0].offsetIndex)
        val wrapped = ParquetFooterParser.parse(file { offsetIndex = (Long.MAX_VALUE - 2) to 8 })
        assertNull(wrapped.rowGroups[0].columns[0].offsetIndex)
    }

    @Test
    fun `a negative null count in the column index is refused`() {
        fun index(nulls: Long) = ThriftCompactWriter().apply {
            structBegin()
            listBegin(1, Type.BOOLEAN_TRUE, 1)
            listBool(false)
            listBegin(2, Type.BINARY, 1)
            listBinary(le64(1))
            listBegin(3, Type.BINARY, 1)
            listBinary(le64(2))
            i32(4, 1)
            listBegin(5, Type.I64, 1)
            listI64(nulls)
            structEnd()
        }.toByteArray()
        assertEquals(0L, ColumnIndex.parse(ByteView(index(0))).nullCounts!![0])
        val e = assertFailsWith<ParquetFormatException> { ColumnIndex.parse(ByteView(index(-1))) }
        assertTrue(e.message, e.message!!.contains("nulls"))
    }

    @Test
    fun `a null_pages list of a non bool type is refused`() {
        fun index(elementType: Int) = ThriftCompactWriter().apply {
            structBegin()
            listBegin(1, elementType, 1)
            // An i32 of 1 is the byte 0x02, which also reads as a false bool.
            if (elementType == Type.I32) listI32(1) else listBool(false)
            listBegin(2, Type.BINARY, 1)
            listBinary(le64(1))
            listBegin(3, Type.BINARY, 1)
            listBinary(le64(2))
            i32(4, 1)
            structEnd()
        }.toByteArray()
        assertEquals(false, ColumnIndex.parse(ByteView(index(Type.BOOLEAN_FALSE))).nullPages[0])
        val e = assertFailsWith<ParquetFormatException> { ColumnIndex.parse(ByteView(index(Type.I32))) }
        assertTrue(e.message, e.message!!.contains("null_pages is not a bool list"))
    }

    /** One V2 data page of three PLAIN INT64 values 1, 2 and 3, with the given V2 header counts. */
    private fun v2Page(numNulls: Int = 0, defLen: Int = 0, repLen: Int = 0): ByteArray {
        val values = le64(1) + le64(2) + le64(3)
        val header = ThriftCompactWriter().apply {
            structBegin()
            i32(1, 3)
            i32(2, values.size)
            i32(3, values.size)
            struct(8) {
                i32(1, 3)
                i32(2, numNulls)
                i32(3, 3)
                i32(4, ParquetEncoding.PLAIN.code)
                i32(5, defLen)
                i32(6, repLen)
                bool(7, false)
            }
            structEnd()
        }.toByteArray()
        return header + values
    }

    @Test
    fun `negative counts in a V2 data page header are refused`() {
        fun read(page: ByteArray): ColumnValues {
            val bytes = file {
                pages = page
                compressed = page.size.toLong()
                uncompressed = page.size.toLong()
            }
            return ParquetFile.wrap(bytes, "v2", testCodecs).readColumn("v")
        }
        assertEquals(listOf(1L, 2L, 3L), (read(v2Page()) as ColumnValues.Int64).values.toList())
        for (page in listOf(v2Page(numNulls = -1), v2Page(defLen = -1), v2Page(repLen = -1))) {
            val e = assertFailsWith<ParquetFormatException> { read(page) }
            assertTrue(e.message, e.message!!.contains("negative page size"))
        }
    }

    private val fixture = "parquet/discover/cells-24h-res7-pageindex.parquet"

    @Test
    fun `a total_compressed_size that would wrap falls back to the end of the column data`() = runTest {
        val bytes = fixtureBytes(fixture)
        val md = ParquetFooterParser.parse(bytes)
        val leaf = md.leaf("h3_index")!!
        val huge = md.copy(rowGroups = md.rowGroups.map { rg -> rg.copy(columns = rg.columns.mapIndexed { i, c -> if (i == leaf.index) c.copy(totalCompressedSize = Long.MAX_VALUE - 2) else c }) })
        val dataEnd = bytes.size - 8L - md.footerLength
        val start = md.rowGroups[0].columns[leaf.index].firstPageOffset

        val range = ColumnChunks(huge, fixture, testCodecs, bytes.size.toLong()).range(leaf, 0)
        assertEquals(start, range.start)
        assertEquals(dataEnd - start, range.length.toLong())
        assertEquals(start until dataEnd, huge.chunkLocation(0, leaf, bytes.size.toLong()).pages)

        // The reader still decodes the chunk from the widened range.
        val cache = FooterCache()
        val source = MemoryRangeSource(bytes, fixture)
        cache.put(source.id(), huge)
        assertEquals(190, ParquetReader.open(source, footerCache = cache).use { it.scan(ScanRequest(listOf("h3_index"))) }.rowCount)
    }

    @Test
    fun `a page header field with the wrong thrift type is refused`() {
        val path = "parquet/device/v1-lz4-90rows-dated.parquet"
        val bytes = fixtureBytes(path)
        val md = ParquetFooterParser.parse(bytes)
        val leaf = md.leaves.first()
        val at = md.rowGroups[0].columns[leaf.index].firstPageOffset.toInt()
        // PageHeader field 1 (type) is an i32: delta 1, wire type 5.
        assertEquals(0x15, bytes[at].toInt() and 0xff)
        ParquetFile.wrap(bytes, path, testCodecs).readColumn(leaf)
        // As an i16 the same varint follows, so only the type check refuses it.
        val bad = bytes.copyOf().also { it[at] = 0x14 }
        val e = assertFailsWith<ParquetFormatException> { ParquetFile.wrap(bad, path, testCodecs).readColumn(leaf) }
        assertTrue(e.message, e.message!!.contains("wire type"))
    }

    private companion object {
        val MAGIC = "PAR1".encodeToByteArray()
    }
}
