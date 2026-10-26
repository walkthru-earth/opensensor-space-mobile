package space.opensensor.data.history.parquet

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** [DeltaBinaryPackedDecoder] and [ByteStreamSplitDecoder] on hand built pages, round trips and corrupt input. */
class ValueDecodersTest {

    private fun delta(bytes: ByteArray) = DeltaBinaryPackedDecoder().reset(bytes, 0, bytes.size, "c", "test")

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test
    fun `the spec example with constant deltas has no miniblock data`() {
        // Block of 128 in 4 miniblocks, 5 values, first 1, minimum delta 1, every width 0.
        val d = delta(bytes(0x80, 0x01, 4, 5, 2, 2, 0, 0, 0, 0))
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), List(5) { d.next() })
    }

    @Test
    fun `the spec example with a negative minimum delta packs two bits LSB first`() {
        // 7 5 3 1 2 3 4 5: minimum delta -2, relative deltas 0 0 0 3 3 3 3 at 2 bits.
        val page = bytes(0x80, 0x01, 4, 8, 14, 3, 2, 0, 0, 0, 0xC0, 0x3F, 0, 0, 0, 0, 0, 0)
        val d = delta(page)
        assertEquals(listOf(7L, 5L, 3L, 1L, 2L, 3L, 4L, 5L), List(8) { d.next() })
        assertFailsWith<ParquetFormatException> { d.next() }
    }

    @Test
    fun `an empty page holds no values`() {
        val d = DeltaBinaryPackedDecoder().reset(ByteArray(4), 2, 2, "c", "test")
        assertFailsWith<ParquetFormatException> { d.next() }
    }

    @Test
    fun `random INT64 values round trip at every bit width up to 64`() {
        val random = Random(94)
        for (case in 0 until 40) {
            val n = 1 + random.nextInt(700)
            val spread = 1L shl random.nextInt(64)
            val values = LongArray(n) { if (it % 53 == 7) listOf(Long.MIN_VALUE, Long.MAX_VALUE)[it % 2] else random.nextLong() % spread }
            for (padded in listOf(true, false)) {
                val d = delta(encodeDelta(values, padded))
                for (i in 0 until n) assertEquals("case $case value $i", values[i], d.next())
            }
        }
    }

    @Test
    fun `INT32 values decode the same from 32 bit wrapping and 64 bit deltas`() {
        val random = Random(59)
        val ints = IntArray(400) { if (it % 2 == 0) Int.MIN_VALUE + random.nextInt(9) else Int.MAX_VALUE - random.nextInt(9) }
        // PyArrow wraps deltas at 32 bits, DuckDB takes them in 64 bits and packs 33 bit miniblocks.
        val wrapped = LongArray(ints.size) { ints[it].toLong() }
        val wide = encodeDelta(wrapped, padded = true)
        assertEquals("64 bit deltas need 33 bits", 33, lastWidths.max())
        val narrow = encodeDelta(wrapped, padded = true, int32 = true)
        assertTrue("32 bit deltas fit 32 bits", lastWidths.max() <= 32)
        for (page in listOf(wide, narrow)) {
            val d = delta(page)
            for (i in ints.indices) assertEquals("value $i", ints[i], d.next().toInt())
        }
    }

    @Test
    fun `a malformed header or miniblock is a format error`() {
        val cases = mapOf(
            "block size not a multiple of 128" to bytes(100, 4, 1, 0),
            "miniblocks not dividing the block" to bytes(0x80, 0x01, 3, 1, 0),
            "miniblocks of 16 values" to bytes(0x80, 0x01, 8, 1, 0),
            "zero miniblocks" to bytes(0x80, 0x01, 0, 1, 0),
            "header cut short" to bytes(0x80, 0x01, 4),
            "varint without an end" to ByteArray(12) { 0xFF.toByte() },
        )
        for ((name, page) in cases) {
            assertFailsWith<ParquetFormatException>(name) { delta(page).next() }
        }
        val wide = delta(bytes(0x80, 0x01, 4, 2, 0, 0, 65, 0, 0, 0))
        wide.next()
        assertFailsWith<ParquetFormatException>("bit width 65") { wide.next() }
        val cut = delta(bytes(0x80, 0x01, 4, 3, 0, 0, 8, 0, 0, 0, 1))
        cut.next()
        cut.next()
        assertFailsWith<ParquetFormatException>("miniblock cut short") { cut.next() }
    }

    @Test
    fun `widths past the last value are not checked`() {
        // Two values: the first miniblock holds the one delta, the other widths are junk.
        val d = delta(bytes(0x80, 0x01, 4, 2, 0, 0, 0, 0xFF, 0xFF, 0xFF))
        assertEquals(listOf(0L, 0L), List(2) { d.next() })
    }

    @Test
    fun `corrupt and truncated delta pages fail only as format errors`() {
        val random = Random(9494)
        val values = LongArray(300) { random.nextLong() shr random.nextInt(64) }
        val page = encodeDelta(values, padded = true)
        for (cut in page.indices) decodeAll(page.copyOf(cut), values.size)
        repeat(200 * fuzzScale) {
            val bad = page.copyOf()
            repeat(1 + random.nextInt(4)) { bad[random.nextInt(bad.size)] = random.nextInt(256).toByte() }
            decodeAll(bad, values.size)
        }
    }

    private fun decodeAll(page: ByteArray, n: Int) {
        try {
            val d = delta(page)
            repeat(n) { d.next() }
        } catch (_: ParquetFormatException) {
            // The contract: any other throwable fails the test.
        }
    }

    @Test
    fun `byte stream split reads byte k of every value from stream k`() {
        val floats = floatArrayOf(1.5f, -0.0f, Float.NaN, Float.MIN_VALUE)
        val doubles = doubleArrayOf(400.25, Double.NEGATIVE_INFINITY, 5e-324)
        for ((width, bits) in listOf(4 to floats.map { it.toRawBits().toLong() and 0xFFFF_FFFFL }, 8 to doubles.map { it.toRawBits() })) {
            val page = ByteArray(width * bits.size)
            for (j in bits.indices) for (k in 0 until width) page[k * bits.size + j] = (bits[j] ushr (8 * k)).toByte()
            val d = ByteStreamSplitDecoder().reset(page, 0, page.size, width, "c", "test")
            assertEquals(bits, List(bits.size) { d.next() })
            assertFailsWith<ParquetFormatException> { d.next() }
        }
    }

    @Test
    fun `byte stream split data that is not whole values is a format error`() {
        assertFailsWith<ParquetFormatException> { ByteStreamSplitDecoder().reset(ByteArray(10), 0, 10, 4, "c", "test") }
        assertFailsWith<ParquetFormatException> { ByteStreamSplitDecoder().reset(ByteArray(10), 0, 7, 8, "c", "test") }
    }

    /** The miniblock widths the last [encodeDelta] wrote. */
    private val lastWidths = ArrayList<Int>()

    /**
     * A DELTA_BINARY_PACKED writer for the tests: blocks of 128 in 4 miniblocks of 32. [int32] wraps
     * deltas at 32 bits as PyArrow does. [padded] false leaves the last miniblock short, which the spec
     * forbids but readers meet.
     */
    private fun encodeDelta(values: LongArray, padded: Boolean, int32: Boolean = false): ByteArray {
        lastWidths.clear()
        val out = ArrayList<Byte>()
        fun uleb(v: Long) {
            var x = v
            while (true) {
                val low = (x and 0x7F).toInt()
                x = x ushr 7
                if (x == 0L) {
                    out += low.toByte()
                    return
                }
                out += (low or 0x80).toByte()
            }
        }
        fun zigzag(v: Long) = uleb((v shl 1) xor (v shr 63))
        uleb(128)
        uleb(4)
        uleb(values.size.toLong())
        zigzag(values.firstOrNull() ?: 0L)
        val deltas = LongArray(maxOf(values.size - 1, 0)) {
            if (int32) (values[it + 1].toInt() - values[it].toInt()).toLong() else values[it + 1] - values[it]
        }
        var at = 0
        while (at < deltas.size) {
            val block = deltas.copyOfRange(at, minOf(at + 128, deltas.size))
            val min = block.min()
            zigzag(min)
            val relative = LongArray(block.size) {
                if (int32) (block[it].toInt() - min.toInt()).toLong() and 0xFFFF_FFFFL else block[it] - min
            }
            val widths = IntArray(4) { m ->
                val slice = relative.drop(m * 32).take(32)
                if (slice.isEmpty()) 0 else 64 - slice.fold(0L) { a, v -> a or v }.countLeadingZeroBits()
            }
            widths.forEach { out += it.toByte() }
            lastWidths += widths.toList()
            for (m in 0 until 4) {
                val slice = relative.drop(m * 32).take(32)
                if (slice.isEmpty()) break
                val w = widths[m]
                val count = if (padded || at + (m + 1) * 32 <= deltas.size) 32 else slice.size
                val packed = ByteArray((count * w + 7) / 8)
                for (i in 0 until count) {
                    val v = if (i < slice.size) slice[i] else 0L
                    for (b in 0 until w) {
                        if ((v ushr b) and 1L != 0L) {
                            val bit = i * w + b
                            packed[bit / 8] = (packed[bit / 8].toInt() or (1 shl (bit % 8))).toByte()
                        }
                    }
                }
                packed.forEach { out += it }
            }
            at += 128
        }
        return out.toByteArray()
    }
}
