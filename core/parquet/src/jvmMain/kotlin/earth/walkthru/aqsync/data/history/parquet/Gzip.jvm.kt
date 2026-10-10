package earth.walkthru.aqsync.data.history.parquet

import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** One raw inflater, CRC and probe byte per thread, reset per member instead of a new stream per page. */
private class GzipState {
    val inflater = Inflater(true)
    val crc = CRC32()
    val probe = ByteArray(1)
}

private val gzipState = ThreadLocal.withInitial { GzipState() }

private const val FHCRC = 2
private const val FEXTRA = 4
private const val FNAME = 8
private const val FCOMMENT = 16

internal actual fun gunzipInto(src: ByteArray, srcOffset: Int, srcLength: Int, dst: ByteArray, dstOffset: Int, dstLength: Int): Int {
    if (srcLength <= 0) throw ParquetFormatException("gzip: empty input")
    if (srcOffset < 0 || srcOffset.toLong() + srcLength > src.size) throw ParquetFormatException("gzip: input window outside the buffer")
    if (dstOffset < 0 || dstLength < 0 || dstOffset.toLong() + dstLength > dst.size) throw ParquetFormatException("gzip: output window outside the buffer")
    val state = gzipState.get()
    val inflater = state.inflater
    val end = srcOffset + srcLength
    val dstEnd = dstOffset + dstLength
    var ip = srcOffset
    var op = dstOffset
    try {
        while (ip < end) {
            ip = skipHeader(src, ip, end)
            inflater.reset()
            inflater.setInput(src, ip, end - ip)
            val memberStart = op
            while (!inflater.finished()) {
                val n = if (op < dstEnd) inflater.inflate(dst, op, dstEnd - op) else inflater.inflate(state.probe, 0, 1)
                if (op == dstEnd && n > 0) throw ParquetFormatException("gzip: page holds more than $dstLength bytes")
                op += n
                if (n == 0 && !inflater.finished()) {
                    when {
                        inflater.needsInput() -> throw ParquetFormatException("gzip: stream truncated")
                        inflater.needsDictionary() -> throw ParquetFormatException("gzip: preset dictionary is not supported")
                        op < dstEnd -> throw ParquetFormatException("gzip: inflater made no progress")
                    }
                }
            }
            state.crc.reset()
            state.crc.update(dst, memberStart, op - memberStart)
            ip = end - inflater.remaining
            if (end - ip < 8) throw ParquetFormatException("gzip: trailer truncated")
            if ((leInt(src, ip).toLong() and 0xFFFF_FFFFL) != state.crc.value) throw ParquetFormatException("gzip: CRC mismatch")
            if (leInt(src, ip + 4) != op - memberStart) throw ParquetFormatException("gzip: member size mismatch")
            ip += 8
        }
    } catch (e: DataFormatException) {
        throw ParquetFormatException("gzip: ${e.message}", e)
    }
    if (op != dstEnd) throw ParquetFormatException("gzip: produced ${op - dstOffset} of $dstLength bytes")
    return dstLength
}

/** Skips one RFC 1952 member header at [at] and returns where the deflate data starts. */
private fun skipHeader(b: ByteArray, at: Int, end: Int): Int {
    fun u8(i: Int): Int = if (i < end) b[i].toInt() and 0xFF else throw ParquetFormatException("gzip: header truncated")
    if (u8(at) != 0x1f || u8(at + 1) != 0x8b) throw ParquetFormatException("gzip: not a gzip member")
    if (u8(at + 2) != 8) throw ParquetFormatException("gzip: compression method ${u8(at + 2)} is not deflate")
    val flags = u8(at + 3)
    if (flags and 0xE0 != 0) throw ParquetFormatException("gzip: reserved header flags set")
    var p = at + 10
    if (flags and FEXTRA != 0) p += 2 + (u8(p) or (u8(p + 1) shl 8))
    if (flags and FNAME != 0) { while (u8(p) != 0) p++; p++ }
    if (flags and FCOMMENT != 0) { while (u8(p) != 0) p++; p++ }
    if (flags and FHCRC != 0) p += 2
    if (p > end) throw ParquetFormatException("gzip: header truncated")
    return p
}
