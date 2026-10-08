package earth.walkthru.aqsync.data.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ParquetVerifyTest {
    /** Smallest structurally valid file: magic, body, footer length, magic. */
    private fun fakeParquet(body: Int = 300): ByteArray {
        val footer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(body).array()
        return "PAR1".toByteArray() + ByteArray(body) { (it * 7).toByte() } + footer + "PAR1".toByteArray()
    }

    @Test
    fun `crc32 matches the IEEE value the firmware prints`() {
        // CRC-32 of "123456789" is the standard check value 0xCBF43926.
        assertEquals(0xCBF43926L, ParquetVerify.crc32("123456789".toByteArray()))
    }

    @Test
    fun `complete file verifies`() {
        val bytes = fakeParquet()
        val result = ParquetVerify.verify(bytes, bytes.size.toLong(), ParquetVerify.crc32(bytes))
        assertTrue(result.describe(), result.ok)
    }

    @Test
    fun `short transfer is a size mismatch`() {
        val bytes = fakeParquet()
        val result = ParquetVerify.verify(bytes.copyOf(bytes.size - 1), bytes.size.toLong(), ParquetVerify.crc32(bytes))
        assertTrue(result is ParquetVerify.Result.SizeMismatch)
    }

    @Test
    fun `one flipped byte is a crc mismatch`() {
        val bytes = fakeParquet()
        val expected = ParquetVerify.crc32(bytes)
        bytes[100] = (bytes[100].toInt() xor 0x01).toByte()
        val result = ParquetVerify.verify(bytes, bytes.size.toLong(), expected)
        assertTrue(result is ParquetVerify.Result.CrcMismatch)
    }

    @Test
    fun `right size and crc but no magic is still rejected`() {
        val bytes = ByteArray(64) { 0x41 }
        val result = ParquetVerify.verify(bytes, 64, ParquetVerify.crc32(bytes))
        assertEquals(ParquetVerify.Result.BadMagic, result)
    }

    @Test
    fun `file based verification agrees with in-memory verification`() {
        val bytes = fakeParquet(2000)
        val file = File.createTempFile("aqsync", ".parquet").apply { deleteOnExit(); writeBytes(bytes) }
        assertTrue(ParquetVerify.verify(file, bytes.size.toLong(), ParquetVerify.crc32(bytes)).ok)
        assertEquals(ParquetVerify.sha256Hex(bytes), ParquetVerify.sha256Hex(file))
        assertTrue(ParquetVerify.verify(file, bytes.size.toLong(), ParquetVerify.crc32(bytes) xor 1L) is ParquetVerify.Result.CrcMismatch)
    }
}
