package earth.walkthru.aqsync.archive

import earth.walkthru.aqsync.data.history.parquet.ByteArrayReader
import earth.walkthru.aqsync.data.history.parquet.ByteReader
import earth.walkthru.aqsync.data.protocol.ParquetVerify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ArchiveVerifierTest {
    /** `PAR1`, a body, a footer length and `PAR1`. Enough for the completion rule, which never parses. */
    private fun parquetLike(body: Int): ByteArray = ParquetVerify.MAGIC + ByteArray(body) { (it * 31).toByte() } + byteArrayOf(0, 0, 0, 0) + ParquetVerify.MAGIC

    /** Counts reads, to prove a wrong size is never hashed. */
    private class CountingReader(bytes: ByteArray) : ByteReader {
        private val inner = ByteArrayReader(bytes)
        var reads = 0
        override val size: Long get() = inner.size
        override fun read(offset: Long, length: Int): ByteArray = inner.read(offset, length).also { reads++ }
    }

    @Test
    fun `a complete file passes and its CRC matches the one shot value`() {
        val bytes = parquetLike(200_000) // more than three chunks
        val crc = ParquetVerify.crc32(bytes)
        assertEquals(crc, ArchiveVerifier.crc32(ByteArrayReader(bytes)))
        assertEquals(ParquetVerify.Result.Ok, ArchiveVerifier.verify(ByteArrayReader(bytes), PartialMeta(bytes.size.toLong(), crc)))
    }

    @Test
    fun `every byte reaches the observer once and in order`() {
        val bytes = parquetLike(150_000)
        val seen = ArrayList<Byte>()
        ArchiveVerifier.verify(ByteArrayReader(bytes), bytes.size.toLong(), ParquetVerify.crc32(bytes)) { chunk, n -> for (i in 0 until n) seen += chunk[i] }
        assertTrue(bytes.contentEquals(seen.toByteArray()))
    }

    @Test
    fun `a wrong size is reported first and the file is never read`() {
        val bytes = parquetLike(100)
        val reader = CountingReader(bytes)
        val result = ArchiveVerifier.verify(reader, bytes.size + 1L, ParquetVerify.crc32(bytes))
        assertEquals(ParquetVerify.Result.SizeMismatch(bytes.size + 1L, bytes.size.toLong()), result)
        assertEquals(0, reader.reads)
    }

    @Test
    fun `a wrong CRC and a missing magic have their own reasons`() {
        val bytes = parquetLike(100)
        val crc = ParquetVerify.crc32(bytes)
        assertIs<ParquetVerify.Result.CrcMismatch>(ArchiveVerifier.verify(ByteArrayReader(bytes), bytes.size.toLong(), crc xor 1))
        val noTail = bytes.copyOf().also { it[it.size - 1] = 0 }
        assertEquals(ParquetVerify.Result.BadMagic, ArchiveVerifier.verify(ByteArrayReader(noTail), noTail.size.toLong(), ParquetVerify.crc32(noTail)))
        val tiny = ByteArray(8)
        assertEquals(ParquetVerify.Result.BadMagic, ArchiveVerifier.verify(ByteArrayReader(tiny), 8, ParquetVerify.crc32(tiny)))
        val empty = ByteArray(0)
        assertEquals(ParquetVerify.Result.BadMagic, ArchiveVerifier.verify(ByteArrayReader(empty), 0, 0))
    }
}
