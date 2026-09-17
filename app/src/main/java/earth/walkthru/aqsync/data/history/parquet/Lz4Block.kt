package earth.walkthru.aqsync.data.history.parquet

import java.nio.ByteBuffer

/**
 * LZ4 *block* decompressor (the format `LZ4_RAW` pages use — no frame header, no checksums).
 * ~60 lines instead of a native dependency: the firmware's pages are ≤ a few KiB and there is one
 * per column chunk, so speed is irrelevant next to correctness and portability (the Swift port is
 * the same loop).
 *
 * Reference: lz4 block format description (`lz4_Block_format.md`).
 */
internal object Lz4Block {

    /**
     * Decompresses exactly [dstLength] bytes from [src] (position..limit) into a new array.
     * @throws ParquetFormatException on a malformed block or a size mismatch.
     */
    fun decompress(src: ByteBuffer, dstLength: Int): ByteArray {
        val out = ByteArray(dstLength)
        var op = 0
        var ip = src.position()
        val end = src.limit()
        fun byteAt(i: Int): Int {
            if (i >= end) throw ParquetFormatException("LZ4 block truncated")
            return src.get(i).toInt() and 0xFF
        }
        fun readLength(base: Int): Int {
            var length = base
            if (base == 15) {
                while (true) {
                    val b = byteAt(ip++)
                    length += b
                    if (b != 255) break
                    if (length < 0) throw ParquetFormatException("LZ4 length overflow")
                }
            }
            return length
        }
        while (ip < end) {
            val token = byteAt(ip++)
            val literalLength = readLength(token ushr 4)
            if (ip + literalLength > end || op + literalLength > dstLength) throw ParquetFormatException("LZ4 literals exceed bounds")
            val dup = src.duplicate()
            dup.position(ip)
            dup.get(out, op, literalLength)
            ip += literalLength
            op += literalLength
            if (ip >= end) break // last sequence carries only literals
            val offset = byteAt(ip) or (byteAt(ip + 1) shl 8)
            ip += 2
            if (offset == 0 || offset > op) throw ParquetFormatException("LZ4 match offset $offset out of range at $op")
            val matchLength = readLength(token and 0x0F) + 4
            if (op + matchLength > dstLength) throw ParquetFormatException("LZ4 match exceeds output")
            var from = op - offset
            // Byte-by-byte on purpose: overlapping matches (offset < length) must replicate.
            repeat(matchLength) { out[op++] = out[from++] }
        }
        if (op != dstLength) throw ParquetFormatException("LZ4 block produced $op of $dstLength bytes")
        return out
    }
}
