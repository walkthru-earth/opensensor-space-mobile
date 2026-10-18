package earth.walkthru.aqsync.data.history.parquet

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * One focused test per older reader guard that the file level suites cannot reach, because an earlier
 * check refuses the same input first (#130). Each test calls the guarded function directly and fails
 * when its guard is removed.
 */
class ReaderGuardsTest {

    @Test
    fun `a negative page size never reaches the codec`() {
        val calls = mutableListOf<Pair<Int, Int>>()
        val codec = PageDecompressor { _, _, srcLength, _, _, dstLength ->
            calls += srcLength to dstLength
            dstLength
        }
        for ((compressed, size) in listOf(-1 to 4, 4 to -1, Int.MIN_VALUE to Int.MIN_VALUE)) {
            val e = assertFailsWith<ParquetFormatException> {
                decompressPage(codec, ParquetCodec.SNAPPY, ByteArray(8), 0, compressed, ByteArray(8), size, "c", "test")
            }
            assertTrue(e.message!!.contains("negative page size"), e.message)
        }
        assertEquals(emptyList<Pair<Int, Int>>(), calls)
        decompressPage(codec, ParquetCodec.SNAPPY, ByteArray(8), 0, 4, ByteArray(8), 6, "c", "test")
        assertEquals(listOf(4 to 6), calls)
    }

    @Test
    fun `a window stays inside its parent cursor and not just inside the array`() {
        val parent = ByteCursor(ByteArray(16), start = 4, limit = 10)
        assertEquals(3, parent.window(5, 3).size)
        assertEquals(6, parent.window(4, 6).size)
        // Each of these lies inside the 16 byte array, so only the parent's own check refuses it.
        for ((from, length) in listOf(0 to 4, 3 to 2, 8 to 4, 10 to 1, 4 to 7)) {
            assertFailsWith<ParquetBoundsException>("window($from, $length)") { parent.window(from, length) }
        }
        assertFailsWith<ParquetBoundsException> { parent.window(5, -1) }
    }

    @Test
    fun `the RLE decoder refuses a bit width outside 0 to 32`() {
        val page = ByteArray(16)
        for (width in listOf(33, 64, -1, Int.MAX_VALUE)) {
            val e = assertFailsWith<ParquetFormatException>("width $width") { RleDecoder().reset(page, 0, page.size, width, "c", "test") }
            assertTrue(e.message!!.contains("bit width $width"), e.message)
        }
        // A run of 8 copies of 1 at width 32 reads back.
        val run = byteArrayOf(16, 1, 0, 0, 0)
        val rle = RleDecoder().reset(run, 0, run.size, 32, "c", "test")
        assertEquals(List(8) { 1 }, List(8) { rle.next() })
    }

    @Test
    fun `a Thrift list cannot claim more elements than bytes left`() {
        // List header, size nibble 0xF (size follows as a varint) and element type i32 (5).
        fun header(size: Int, tail: Int): ThriftCompactReader {
            val out = ByteOutput()
            out.write(0xF5)
            var v = size
            while (v and 0x7F.inv() != 0) {
                out.write((v and 0x7F) or 0x80)
                v = v ushr 7
            }
            out.write(v)
            repeat(tail) { out.write(0) }
            val bytes = out.toByteArray()
            return ThriftCompactReader(ByteCursor(bytes))
        }
        assertEquals(3, header(3, tail = 3).readListHeader().size)
        for ((size, tail) in listOf(4 to 3, 1_000_000 to 8, Int.MAX_VALUE to 0)) {
            val e = assertFailsWith<IllegalArgumentException>("size $size") { header(size, tail).readListHeader() }
            assertTrue(e.message!!.contains("list of $size elements"), e.message)
        }
    }
}
