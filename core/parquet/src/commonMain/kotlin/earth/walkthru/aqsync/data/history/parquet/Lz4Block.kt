package earth.walkthru.aqsync.data.history.parquet

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
     * Decompresses exactly [dstLength] bytes from `src[srcOffset, srcOffset + srcLength)` into a new array.
     * @throws ParquetFormatException on a malformed block or a size mismatch.
     */
    fun decompress(src: ByteArray, dstLength: Int, srcOffset: Int = 0, srcLength: Int = src.size - srcOffset): ByteArray {
        if (dstLength < 0) throw ParquetFormatException("LZ4 negative output size")
        val out = ByteArray(dstLength)
        decompressInto(src, srcOffset, srcLength, out, 0, dstLength)
        return out
    }

    /**
     * Decompresses `src[srcOffset, srcOffset + srcLength)` into exactly `dst[dstOffset, dstOffset + dstLength)`.
     * @return [dstLength].
     * @throws ParquetFormatException on a malformed block or a size mismatch.
     */
    fun decompressInto(src: ByteArray, srcOffset: Int, srcLength: Int, dst: ByteArray, dstOffset: Int, dstLength: Int): Int {
        if (dstLength < 0) throw ParquetFormatException("LZ4 negative output size")
        val end = srcOffset + srcLength
        if (srcOffset < 0 || srcLength < 0 || end > src.size) throw ParquetFormatException("LZ4 input window outside the buffer")
        if (dstOffset < 0 || dstOffset.toLong() + dstLength > dst.size) throw ParquetFormatException("LZ4 output window outside the buffer")
        val dstEnd = dstOffset + dstLength
        var op = dstOffset
        var ip = srcOffset
        fun byteAt(i: Int): Int {
            if (i >= end) throw ParquetFormatException("LZ4 block truncated")
            return src[i].toInt() and 0xFF
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
            if (ip + literalLength > end || op + literalLength > dstEnd) throw ParquetFormatException("LZ4 literals exceed bounds")
            src.copyInto(dst, op, ip, ip + literalLength)
            ip += literalLength
            op += literalLength
            if (ip >= end) break // last sequence carries only literals
            val offset = byteAt(ip) or (byteAt(ip + 1) shl 8)
            ip += 2
            if (offset == 0 || offset > op - dstOffset) throw ParquetFormatException("LZ4 match offset $offset out of range at ${op - dstOffset}")
            val matchLength = readLength(token and 0x0F) + 4
            if (op + matchLength > dstEnd) throw ParquetFormatException("LZ4 match exceeds output")
            var from = op - offset
            // Byte-by-byte on purpose: overlapping matches (offset < length) must replicate.
            repeat(matchLength) { dst[op++] = dst[from++] }
        }
        if (op != dstEnd) throw ParquetFormatException("LZ4 block produced ${op - dstOffset} of $dstLength bytes")
        return dstLength
    }

    /**
     * Compresses [src] as one LZ4 block (greedy, 4-byte hash of the last seen position — the classic
     * "fast" scheme). Not the reference implementation's speed, but the reference decoder reads it,
     * and so does PyArrow. Obeys the format's end-of-block rules: the last match starts ≥ 12 bytes
     * before the end and the last 5 bytes are literals.
     */
    fun compress(src: ByteArray): ByteArray {
        val n = src.size
        val out = ByteOutput(n / 2 + 16)
        if (n == 0) return out.toByteArray()
        val table = IntArray(1 shl 12) { -1 }
        fun read32(i: Int): Int = (src[i].toInt() and 0xFF) or ((src[i + 1].toInt() and 0xFF) shl 8) or ((src[i + 2].toInt() and 0xFF) shl 16) or ((src[i + 3].toInt() and 0xFF) shl 24)
        fun hash(v: Int): Int = (v * -1640531535) ushr (32 - 12)
        fun writeLength(base: Int) {
            var rest = base
            while (rest >= 255) { out.write(255); rest -= 255 }
            out.write(rest)
        }
        fun emit(literalStart: Int, literalLen: Int, offset: Int, matchLen: Int) {
            val ml = matchLen - 4
            out.write((minOf(literalLen, 15) shl 4) or (if (matchLen == 0) 0 else minOf(ml, 15)))
            if (literalLen >= 15) writeLength(literalLen - 15)
            out.write(src, literalStart, literalLen)
            if (matchLen == 0) return
            out.write(offset and 0xFF)
            out.write(offset ushr 8)
            if (ml >= 15) writeLength(ml - 15)
        }
        var anchor = 0
        var ip = 0
        val matchLimit = n - 12
        while (ip < matchLimit) {
            val h = hash(read32(ip))
            val ref = table[h]
            table[h] = ip
            if (ref >= 0 && ip - ref < 65536 && read32(ref) == read32(ip)) {
                var len = 4
                val maxLen = n - 5 - ip
                while (len < maxLen && src[ref + len] == src[ip + len]) len++
                emit(anchor, ip - anchor, ip - ref, len)
                ip += len
                anchor = ip
            } else {
                ip++
            }
        }
        emit(anchor, n - anchor, 0, 0)
        return out.toByteArray()
    }
}
