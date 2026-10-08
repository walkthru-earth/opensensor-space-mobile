package earth.walkthru.aqsync.data.history.parquet

/**
 * Decompresses one Parquet page body. Implementations are stateless and thread-safe.
 *
 * [input] holds exactly the compressed bytes of the page and belongs to the decompressor (it may keep or
 * modify it). The result must hold exactly [uncompressedSize] bytes (the page header says so) or the
 * caller throws a [ParquetFormatException].
 */
fun interface PageDecompressor {
    fun decompress(input: ByteArray, uncompressedSize: Int): ByteArray
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
                ParquetCodec.UNCOMPRESSED to PageDecompressor { input, size ->
                    if (input.size != size) throw ParquetFormatException("uncompressed page sizes disagree (${input.size} vs $size)")
                    input
                },
                ParquetCodec.LZ4_RAW to PageDecompressor { input, size -> Lz4Block.decompress(input, size) },
                ParquetCodec.SNAPPY to PageDecompressor { input, size -> Snappy.decompress(input, size) },
                ParquetCodec.GZIP to PageDecompressor { input, size -> Gzip.decompress(input, size) },
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
        val src = ByteCursor(input)
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
        val out = ByteArray(expectedSize)
        var pos = 0
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
                    if (len > src.remaining || pos + len > out.size) throw ParquetFormatException("snappy: literal of $len bytes overruns")
                    src.get(out, pos, len)
                    pos += len
                }
                1 -> {
                    val len = 4 + ((tag ushr 2) and 7)
                    val offset = ((tag ushr 5) shl 8) or (src.getOrThrow().toInt() and 0xFF)
                    pos = copy(out, pos, offset, len)
                }
                2 -> {
                    val len = (tag ushr 2) + 1
                    val offset = (src.getOrThrow().toInt() and 0xFF) or ((src.getOrThrow().toInt() and 0xFF) shl 8)
                    pos = copy(out, pos, offset, len)
                }
                else -> {
                    val len = (tag ushr 2) + 1
                    if (src.remaining < 4) throw ParquetFormatException("snappy: truncated copy")
                    val offset = src.getInt()
                    if (offset < 0) throw ParquetFormatException("snappy: copy offset out of range")
                    pos = copy(out, pos, offset, len)
                }
            }
        }
        if (pos != expectedSize) throw ParquetFormatException("snappy: produced $pos of $expectedSize bytes")
        return out
    }

    private fun ByteCursor.getOrThrow(): Byte = if (hasRemaining) get() else throw ParquetFormatException("snappy: truncated block")

    /** Back-reference; [offset] may be smaller than [len] (overlapping copy = run), so copy byte by byte. */
    private fun copy(out: ByteArray, pos: Int, offset: Int, len: Int): Int {
        if (offset <= 0 || offset > pos) throw ParquetFormatException("snappy: copy offset $offset at $pos is invalid")
        if (pos + len > out.size) throw ParquetFormatException("snappy: copy of $len bytes overruns")
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
    fun decompress(input: ByteArray, expectedSize: Int): ByteArray = gunzip(input, expectedSize)
}

/**
 * Platform gzip: `java.util.zip` on the JVM, the system zlib on iOS. Produces exactly [expectedSize]
 * bytes or throws [ParquetFormatException] (short output, trailing data, corrupt stream).
 */
internal expect fun gunzip(input: ByteArray, expectedSize: Int): ByteArray
