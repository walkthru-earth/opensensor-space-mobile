package earth.walkthru.aqsync.data.history.parquet

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream

/**
 * Decompresses one Parquet page body. Implementations are stateless and thread-safe.
 *
 * [input] is a read-only slice of exactly the compressed bytes; the result must hold exactly
 * [uncompressedSize] bytes (the page header says so) or a [ParquetFormatException] is thrown by the caller.
 */
fun interface PageDecompressor {
    fun decompress(input: ByteBuffer, uncompressedSize: Int): ByteArray
}

/**
 * The codecs a [ParquetFile] can decode. [builtIn] needs nothing beyond the JDK: UNCOMPRESSED, LZ4_RAW
 * (own block decoder, the firmware's codec), SNAPPY (own decoder, what PyArrow/DuckDB/GDAL emit by
 * default) and GZIP (`java.util.zip`). ZSTD is not hand-written: `:app` registers `zstd-jni` here and the
 * tests do the same with the JVM jar — see docs/history-ux-and-data-layer.md §7 (G1).
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
                    if (input.remaining() != size) throw ParquetFormatException("uncompressed page sizes disagree (${input.remaining()} vs $size)")
                    ByteArray(size).also { input.duplicate().get(it) }
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
    fun decompress(input: ByteBuffer, expectedSize: Int): ByteArray {
        val src = input.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        // Preamble: uncompressed length as a little-endian varint.
        var length = 0L
        var shift = 0
        while (true) {
            if (!src.hasRemaining()) throw ParquetFormatException("snappy: truncated length")
            val b = src.get().toInt() and 0xFF
            length = length or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            if (shift > 32) throw ParquetFormatException("snappy: length varint too long")
        }
        if (length != expectedSize.toLong()) throw ParquetFormatException("snappy: block declares $length bytes, page header says $expectedSize")
        val out = ByteArray(expectedSize)
        var pos = 0
        while (src.hasRemaining()) {
            val tag = src.get().toInt() and 0xFF
            when (tag and 3) {
                0 -> {
                    var len = tag ushr 2
                    if (len >= 60) {
                        val bytes = len - 59
                        len = 0
                        for (i in 0 until bytes) len = len or ((src.getOrThrow().toInt() and 0xFF) shl (8 * i))
                    }
                    len += 1
                    if (len > src.remaining() || pos + len > out.size) throw ParquetFormatException("snappy: literal of $len bytes overruns")
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
                    if (src.remaining() < 4) throw ParquetFormatException("snappy: truncated copy")
                    val offset = src.getInt()
                    if (offset < 0) throw ParquetFormatException("snappy: copy offset out of range")
                    pos = copy(out, pos, offset, len)
                }
            }
        }
        if (pos != expectedSize) throw ParquetFormatException("snappy: produced $pos of $expectedSize bytes")
        return out
    }

    private fun ByteBuffer.getOrThrow(): Byte = if (hasRemaining()) get() else throw ParquetFormatException("snappy: truncated block")

    /** Back-reference; [offset] may be smaller than [len] (overlapping copy = run), so copy byte by byte. */
    private fun copy(out: ByteArray, pos: Int, offset: Int, len: Int): Int {
        if (offset <= 0 || offset > pos) throw ParquetFormatException("snappy: copy offset $offset at $pos is invalid")
        if (pos + len > out.size) throw ParquetFormatException("snappy: copy of $len bytes overruns")
        if (offset >= len) {
            System.arraycopy(out, pos - offset, out, pos, len)
        } else {
            for (i in 0 until len) out[pos + i] = out[pos - offset + i]
        }
        return pos + len
    }
}

/** Parquet GZIP = a gzip member stream (RFC 1952) around the page. */
object Gzip {
    fun decompress(input: ByteBuffer, expectedSize: Int): ByteArray {
        val bytes = ByteArray(input.remaining()).also { input.duplicate().get(it) }
        val out = ByteArray(expectedSize)
        try {
            GZIPInputStream(ByteArrayInputStream(bytes)).use { zin ->
                var pos = 0
                while (pos < expectedSize) {
                    val n = zin.read(out, pos, expectedSize - pos)
                    if (n < 0) break
                    pos += n
                }
                if (pos != expectedSize) throw ParquetFormatException("gzip: produced $pos of $expectedSize bytes")
                if (zin.read() != -1) throw ParquetFormatException("gzip: page holds more than $expectedSize bytes")
            }
        } catch (e: java.io.IOException) {
            throw ParquetFormatException("gzip: ${e.message}", e)
        }
        return out
    }
}
