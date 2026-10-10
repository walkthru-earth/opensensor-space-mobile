package earth.walkthru.aqsync.data.history.parquet

/**
 * Decompresses one Parquet page body. Implementations are thread-safe and may keep per-thread state
 * (an inflater, a ZSTD context) between calls.
 *
 * The compressed page is `src[srcOffset, srcOffset + srcLength)`, a window of the bytes the reader
 * fetched, which the decompressor must not modify or keep. It writes exactly [dstLength] bytes (the
 * page header says so) into `dst[dstOffset, dstOffset + dstLength)` and returns the count written; the
 * caller throws a [ParquetFormatException] for any other count.
 */
fun interface PageDecompressor {
    fun decompress(src: ByteArray, srcOffset: Int, srcLength: Int, dst: ByteArray, dstOffset: Int, dstLength: Int): Int
}

/**
 * The codecs a [ParquetFile] can decode. [builtIn] needs nothing beyond the platform: UNCOMPRESSED, LZ4_RAW
 * (own block decoder, the firmware's codec), SNAPPY (own decoder, what PyArrow/DuckDB/GDAL emit by
 * default) and GZIP (`java.util.zip` on the JVM, the system zlib on iOS). ZSTD is not hand-written: `:app`
 * registers `zstd-jni` here and the JVM tests do the same with the JVM jar (docs/parquet.md).
 */
class ParquetCodecs private constructor(private val table: Map<ParquetCodec, PageDecompressor>) {

    operator fun get(codec: ParquetCodec): PageDecompressor? = table[codec]

    fun supports(codec: ParquetCodec): Boolean = codec in table

    /** A registry with [decompressor] added for [codec] (replacing any previous one). */
    fun with(codec: ParquetCodec, decompressor: PageDecompressor): ParquetCodecs = ParquetCodecs(table + (codec to decompressor))

    val supported: Set<ParquetCodec> get() = table.keys

    companion object {
        val builtIn: ParquetCodecs = ParquetCodecs(
            mapOf(
                ParquetCodec.UNCOMPRESSED to PageDecompressor { src, srcOffset, srcLength, dst, dstOffset, dstLength ->
                    if (srcLength != dstLength) throw ParquetFormatException("uncompressed page sizes disagree ($srcLength vs $dstLength)")
                    src.copyInto(dst, dstOffset, srcOffset, srcOffset + srcLength)
                    dstLength
                },
                ParquetCodec.LZ4_RAW to PageDecompressor(Lz4Block::decompressInto),
                ParquetCodec.SNAPPY to PageDecompressor(Snappy::decompressInto),
                ParquetCodec.GZIP to PageDecompressor(::gunzipInto),
            ),
        )
    }
}

/**
 * Snappy raw block format (what Parquet stores; no framing): a varint uncompressed length, then a stream
 * of literals and back-references. Decoder only; ~60 lines, spec at google/snappy `format_description.txt`.
 */
object Snappy {
    fun decompress(input: ByteArray, expectedSize: Int): ByteArray {
        if (expectedSize < 0) throw ParquetFormatException("snappy: negative output size")
        val out = ByteArray(expectedSize)
        decompressInto(input, 0, input.size, out, 0, expectedSize)
        return out
    }

    /** Decodes `input[srcOffset, srcOffset + srcLength)` into exactly `out[dstOffset, dstOffset + expectedSize)`. */
    fun decompressInto(input: ByteArray, srcOffset: Int, srcLength: Int, out: ByteArray, dstOffset: Int, expectedSize: Int): Int {
        if (srcOffset < 0 || srcLength < 0 || srcOffset.toLong() + srcLength > input.size) throw ParquetFormatException("snappy: input window outside the buffer")
        if (dstOffset < 0 || expectedSize < 0 || dstOffset.toLong() + expectedSize > out.size) throw ParquetFormatException("snappy: output window outside the buffer")
        val src = ByteCursor(input, srcOffset, srcOffset + srcLength)
        // Preamble: uncompressed length as a little-endian varint.
        var length = 0L
        var shift = 0
        while (true) {
            if (!src.hasRemaining) throw ParquetFormatException("snappy: truncated length")
            val b = src.getU8()
            length = length or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            if (shift > 32) throw ParquetFormatException("snappy: length varint too long")
        }
        if (length != expectedSize.toLong()) throw ParquetFormatException("snappy: block declares $length bytes, page header says $expectedSize")
        val end = dstOffset + expectedSize
        var pos = dstOffset
        while (src.hasRemaining) {
            val tag = src.getU8()
            when (tag and 3) {
                0 -> {
                    var len = tag ushr 2
                    if (len >= 60) {
                        val bytes = len - 59
                        len = 0
                        for (i in 0 until bytes) len = len or ((src.getOrThrow().toInt() and 0xFF) shl (8 * i))
                    }
                    len += 1
                    if (len > src.remaining || pos + len > end) throw ParquetFormatException("snappy: literal of $len bytes overruns")
                    src.get(out, pos, len)
                    pos += len
                }
                1 -> {
                    val len = 4 + ((tag ushr 2) and 7)
                    val offset = ((tag ushr 5) shl 8) or (src.getOrThrow().toInt() and 0xFF)
                    pos = copy(out, dstOffset, end, pos, offset, len)
                }
                2 -> {
                    val len = (tag ushr 2) + 1
                    val offset = (src.getOrThrow().toInt() and 0xFF) or ((src.getOrThrow().toInt() and 0xFF) shl 8)
                    pos = copy(out, dstOffset, end, pos, offset, len)
                }
                else -> {
                    val len = (tag ushr 2) + 1
                    if (src.remaining < 4) throw ParquetFormatException("snappy: truncated copy")
                    val offset = src.getInt()
                    if (offset < 0) throw ParquetFormatException("snappy: copy offset out of range")
                    pos = copy(out, dstOffset, end, pos, offset, len)
                }
            }
        }
        if (pos != end) throw ParquetFormatException("snappy: produced ${pos - dstOffset} of $expectedSize bytes")
        return expectedSize
    }

    private fun ByteCursor.getOrThrow(): Byte = if (hasRemaining) get() else throw ParquetFormatException("snappy: truncated block")

    /** Back-reference; [offset] may be smaller than [len] (overlapping copy = run), so copy byte by byte. */
    private fun copy(out: ByteArray, base: Int, end: Int, pos: Int, offset: Int, len: Int): Int {
        if (offset <= 0 || offset > pos - base) throw ParquetFormatException("snappy: copy offset $offset at ${pos - base} is invalid")
        if (pos + len > end) throw ParquetFormatException("snappy: copy of $len bytes overruns")
        if (offset >= len) {
            out.copyInto(out, pos, pos - offset, pos - offset + len)
        } else {
            for (i in 0 until len) out[pos + i] = out[pos - offset + i]
        }
        return pos + len
    }
}

/** Parquet GZIP = a gzip member stream (RFC 1952) around the page. */
object Gzip {
    /** Inflates [input] into exactly [expectedSize] bytes. */
    fun decompress(input: ByteArray, expectedSize: Int): ByteArray {
        if (expectedSize < 0) throw ParquetFormatException("gzip: negative output size")
        val out = ByteArray(expectedSize)
        gunzipInto(input, 0, input.size, out, 0, expectedSize)
        return out
    }
}

/**
 * Platform gzip, one or more concatenated members: `java.util.zip.Inflater` on the JVM, the system zlib
 * on iOS, each reused per thread. Writes exactly [dstLength] bytes or throws [ParquetFormatException]
 * (short output, trailing data, corrupt stream, CRC mismatch).
 */
internal expect fun gunzipInto(src: ByteArray, srcOffset: Int, srcLength: Int, dst: ByteArray, dstOffset: Int, dstLength: Int): Int
