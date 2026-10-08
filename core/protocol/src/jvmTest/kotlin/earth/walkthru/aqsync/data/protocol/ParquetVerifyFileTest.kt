package earth.walkthru.aqsync.data.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.CRC32

/** The JVM file and stream adapters, checked against the shared in-memory rule and real device files. */
class ParquetVerifyFileTest {
    private fun fixture(path: String): ByteArray =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("parquet/$path")) { "missing fixture $path" }.use { it.readBytes() }

    @Test
    fun `file based verification agrees with in-memory verification`() {
        val bytes = fakeParquet(2000)
        val file = File.createTempFile("aqsync", ".parquet").apply { deleteOnExit(); writeBytes(bytes) }
        assertTrue(ParquetVerify.verify(file, bytes.size.toLong(), ParquetVerify.crc32(bytes)).ok)
        assertEquals(ParquetVerify.sha256Hex(bytes), ParquetVerify.sha256Hex(file))
        assertTrue(ParquetVerify.verify(file, bytes.size.toLong(), ParquetVerify.crc32(bytes) xor 1L) is ParquetVerify.Result.CrcMismatch)
        assertTrue(ParquetVerify.verify(file, bytes.size + 1L, ParquetVerify.crc32(bytes)) is ParquetVerify.Result.SizeMismatch)
    }

    @Test
    fun `file without magic is rejected`() {
        val bytes = ByteArray(64) { 0x41 }
        val file = File.createTempFile("aqsync", ".parquet").apply { deleteOnExit(); writeBytes(bytes) }
        assertEquals(ParquetVerify.Result.BadMagic, ParquetVerify.verify(file, 64, ParquetVerify.crc32(bytes)))
    }

    @Test
    fun `portable, platform and java crc agree on real device files`() {
        val names = listOf(
            "device/v1-none-90rows-dated.parquet",
            "device/v1-lz4-90rows-dated.parquet",
            "device/v3-board-2rg-180rows.parquet",
            "v3/v3-contract-lz4-anchored.parquet",
        )
        for (name in names) {
            val bytes = fixture(name)
            val java = CRC32().also { it.update(bytes) }.value
            assertEquals(name, java, PortableCrc32().also { it.update(bytes) }.value)
            assertEquals(name, java, ParquetVerify.crc32(bytes))
            assertEquals(name, java, ParquetVerify.crc32(bytes.inputStream()))
            assertTrue(name, ParquetVerify.verify(bytes, bytes.size.toLong(), java).ok)
            val file = File.createTempFile("aqsync", ".parquet").apply { deleteOnExit(); writeBytes(bytes) }
            assertTrue(name, ParquetVerify.verify(file, bytes.size.toLong(), java).ok)
        }
    }
}
