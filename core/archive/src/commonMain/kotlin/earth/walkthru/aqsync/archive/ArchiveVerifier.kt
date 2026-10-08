package earth.walkthru.aqsync.archive

import earth.walkthru.aqsync.data.history.parquet.ByteReader
import earth.walkthru.aqsync.data.protocol.Crc32
import earth.walkthru.aqsync.data.protocol.ParquetVerify

/**
 * The completion check over any stored file, on both phones. A file becomes synced only when this
 * returns [ParquetVerify.Result.Ok]. The rule itself (size, then CRC-32, then `PAR1` head and tail)
 * stays in [ParquetVerify]. This adds the streaming over a [ByteReader], so an adapter only hands out
 * bytes and never re-implements the check.
 */
object ArchiveVerifier {
    /** Bytes read per step while streaming the CRC-32. */
    const val CHUNK: Int = 64 * 1024

    /**
     * Checks [reader] against what OPENED promised. The size is compared first, so a short or long
     * file is never hashed. [onBytes] sees every byte once, in order, while the CRC-32 runs, so a
     * caller can hash the same pass (SHA-256 for the index record).
     */
    fun verify(reader: ByteReader, expected: PartialMeta, onBytes: ((ByteArray, Int) -> Unit)? = null): ParquetVerify.Result =
        verify(reader, expected.size, expected.crc32, onBytes)

    fun verify(reader: ByteReader, expectedSize: Long, expectedCrc32: Long, onBytes: ((ByteArray, Int) -> Unit)? = null): ParquetVerify.Result {
        val size = reader.size
        return ParquetVerify.verify(
            expectedSize = expectedSize,
            expectedCrc32 = expectedCrc32,
            actualSize = size,
            actualCrc32 = { crc32(reader, onBytes) },
            hasMagic = {
                val n = ParquetVerify.MAGIC.size
                ParquetVerify.hasMagic(reader.read(0, n), reader.read(size - n, n))
            },
        )
    }

    /** CRC-32 of every byte of [reader], read [CHUNK] bytes at a time. */
    fun crc32(reader: ByteReader, onBytes: ((ByteArray, Int) -> Unit)? = null): Long {
        val crc = Crc32()
        val size = reader.size
        var offset = 0L
        while (offset < size) {
            val n = minOf(CHUNK.toLong(), size - offset).toInt()
            val bytes = reader.read(offset, n)
            crc.update(bytes, 0, n)
            onBytes?.invoke(bytes, n)
            offset += n
        }
        return crc.value
    }
}
