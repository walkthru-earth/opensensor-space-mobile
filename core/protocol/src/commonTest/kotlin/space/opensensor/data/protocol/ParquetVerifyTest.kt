package space.opensensor.data.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Smallest structurally valid file: magic, body, footer length, magic. */
internal fun fakeParquet(body: Int = 300): ByteArray {
    val footer = ByteArray(4) { i -> (body ushr (8 * i)).toByte() }
    return ParquetVerify.MAGIC + ByteArray(body) { (it * 7).toByte() } + footer + ParquetVerify.MAGIC
}

class ParquetVerifyTest {
    @Test
    fun crc32MatchesTheIeeeCheckValue() {
        // CRC-32 of "123456789" is the standard check value 0xCBF43926.
        assertEquals(0xCBF43926L, ParquetVerify.crc32("123456789".encodeToByteArray()))
        assertEquals(0L, ParquetVerify.crc32(ByteArray(0)))
    }

    @Test
    fun crc32KnownVectors() {
        val vectors = mapOf(
            "a" to 0xE8B7BE43L,
            "abc" to 0x352441C2L,
            "The quick brown fox jumps over the lazy dog" to 0x414FA339L,
        )
        for ((text, expected) in vectors) {
            val bytes = text.encodeToByteArray()
            assertEquals(expected, ParquetVerify.crc32(bytes), text)
            assertEquals(expected, PortableCrc32().also { it.update(bytes) }.value, text)
        }
    }

    @Test
    fun streamingUpdatesMatchOneShot() {
        val bytes = ByteArray(100_000) { (it * 31 + 7).toByte() }
        val expected = ParquetVerify.crc32(bytes)
        val platform = Crc32()
        val portable = PortableCrc32()
        var offset = 0
        var step = 1
        while (offset < bytes.size) {
            val n = minOf(step, bytes.size - offset)
            platform.update(bytes, offset, n)
            portable.update(bytes, offset, n)
            offset += n
            step = step * 3 + 1
        }
        assertEquals(expected, platform.value)
        assertEquals(expected, portable.value)
        platform.reset()
        portable.reset()
        assertEquals(0L, platform.value)
        assertEquals(0L, portable.value)
    }

    @Test
    fun updateRejectsAnOutOfRangeSlice() {
        assertFailsWith<IndexOutOfBoundsException> { Crc32().update(ByteArray(4), 2, 3) }
        assertFailsWith<IndexOutOfBoundsException> { PortableCrc32().update(ByteArray(4), -1, 1) }
    }

    @Test
    fun completeFileVerifies() {
        val bytes = fakeParquet()
        val result = ParquetVerify.verify(bytes, bytes.size.toLong(), ParquetVerify.crc32(bytes))
        assertTrue(result.ok, result.describe())
        assertEquals("verified", result.describe())
    }

    @Test
    fun shortTransferIsASizeMismatch() {
        val bytes = fakeParquet()
        val result = ParquetVerify.verify(bytes.copyOf(bytes.size - 1), bytes.size.toLong(), ParquetVerify.crc32(bytes))
        assertIs<ParquetVerify.Result.SizeMismatch>(result)
    }

    @Test
    fun oneFlippedByteIsACrcMismatch() {
        val bytes = fakeParquet()
        val expected = ParquetVerify.crc32(bytes)
        bytes[100] = (bytes[100].toInt() xor 0x01).toByte()
        val result = ParquetVerify.verify(bytes, bytes.size.toLong(), expected)
        assertIs<ParquetVerify.Result.CrcMismatch>(result)
    }

    @Test
    fun rightSizeAndCrcButNoMagicIsStillRejected() {
        val bytes = ByteArray(64) { 0x41 }
        assertEquals(ParquetVerify.Result.BadMagic, ParquetVerify.verify(bytes, 64, ParquetVerify.crc32(bytes)))
        val tiny = ParquetVerify.MAGIC + ParquetVerify.MAGIC
        assertFalse(ParquetVerify.hasMagic(tiny))
        assertEquals(ParquetVerify.Result.BadMagic, ParquetVerify.verify(tiny, 8, ParquetVerify.crc32(tiny)))
    }

    @Test
    fun headAndTailMagicCheck() {
        val bytes = fakeParquet()
        assertTrue(ParquetVerify.hasMagic(bytes.copyOfRange(0, 4), bytes.copyOfRange(bytes.size - 8, bytes.size)))
        assertFalse(ParquetVerify.hasMagic(ByteArray(4), ParquetVerify.MAGIC))
        assertFalse(ParquetVerify.hasMagic(ParquetVerify.MAGIC, ByteArray(3)))
    }

    @Test
    fun sizeIsCheckedBeforeTheCrcIsComputed() {
        var hashed = false
        val result = ParquetVerify.verify(
            expectedSize = 100,
            expectedCrc32 = 0,
            actualSize = 99,
            actualCrc32 = { hashed = true; 0L },
            hasMagic = { true },
        )
        assertEquals(ParquetVerify.Result.SizeMismatch(100, 99), result)
        assertFalse(hashed)
    }

    @Test
    fun describeFormatsTheCrcAsEightHexDigits() {
        assertEquals(
            "CRC-32 mismatch: expected cbf43926, computed 0000000a",
            ParquetVerify.Result.CrcMismatch(0xCBF43926L, 10L).describe(),
        )
        assertEquals("size mismatch: expected 5 bytes, have 4", ParquetVerify.Result.SizeMismatch(5, 4).describe())
    }
}
