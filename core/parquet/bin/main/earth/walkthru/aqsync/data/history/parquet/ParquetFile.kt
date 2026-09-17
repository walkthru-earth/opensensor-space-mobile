package earth.walkthru.aqsync.data.history.parquet

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Reads the Parquet subset the CoreS3 firmware writes — and nothing else, on purpose:
 *
 * - flat schema, leaves of physical type INT32 / INT64 / FLOAT, REQUIRED or OPTIONAL;
 * - any number of row groups (v1 writer: one; v6 writer: up to eight), one or more Data Page V1 per
 *   column chunk, PLAIN values, RLE/bit-packed definition levels (max level 1), no dictionary pages;
 * - codec UNCOMPRESSED or LZ4_RAW (whole page compressed as one LZ4 block);
 * - footer statistics and the TIMESTAMP logical type are exposed, not required.
 *
 * Anything else raises [ParquetFormatException] naming the feature, so a file from another writer is
 * refused loudly rather than misread. Files are memory-mapped and decoded straight into primitive
 * arrays ([ColumnValues]); a chart that needs three of 77 columns touches three column chunks.
 *
 * Not thread-safe per instance for concurrent reads of the *same* instance is fine (absolute reads,
 * duplicated buffers) — the mapped buffer itself is never repositioned.
 */
class ParquetFile private constructor(
    private val buf: ByteBuffer,
    val metadata: ParquetFileMetadata,
    /** Where the bytes came from, for messages. */
    val source: String,
) {
    val leaves: List<LeafColumn> get() = metadata.leaves
    val numRows: Long get() = metadata.numRows
    val rowGroupCount: Int get() = metadata.rowGroups.size

    fun leaf(name: String): LeafColumn = metadata.leaf(name) ?: throw ParquetFormatException("no column '$name' in $source")

    /** One column across every row group, in row order. */
    fun readColumn(name: String): ColumnValues = readColumn(leaf(name))

    fun readColumn(leaf: LeafColumn): ColumnValues {
        if (metadata.rowGroups.isEmpty()) return empty(leaf)
        return ColumnValues.concat(metadata.rowGroups.indices.map { readColumn(leaf, it) })
    }

    /** Several columns at once; the map keeps the requested order. */
    fun readColumns(names: List<String>): Map<String, ColumnValues> = names.associateWith { readColumn(it) }

    /** One column of one row group. */
    fun readColumn(leaf: LeafColumn, rowGroup: Int): ColumnValues {
        val rg = metadata.rowGroups.getOrNull(rowGroup) ?: throw ParquetFormatException("row group $rowGroup does not exist in $source")
        val chunk = rg.columns[leaf.index]
        if (chunk.name != leaf.name) throw ParquetFormatException("column chunk ${leaf.index} is '${chunk.name}', expected '${leaf.name}' ($source)")
        return ColumnChunkDecoder(buf, source).decode(leaf, chunk, rg.numRows)
    }

    private fun empty(leaf: LeafColumn): ColumnValues = when (leaf.type) {
        ParquetType.INT32 -> ColumnValues.Int32(IntArray(0), null)
        ParquetType.INT64 -> ColumnValues.Int64(LongArray(0), null)
        ParquetType.FLOAT -> ColumnValues.Float32(FloatArray(0), null)
        else -> throw ParquetFormatException("physical type ${leaf.type} of '${leaf.name}' is not supported ($source)")
    }

    companion object {
        /** Memory-maps [file] read-only and parses its footer. The mapping outlives the channel. */
        fun open(file: File): ParquetFile {
            val mapped = RandomAccessFile(file, "r").use { raf ->
                raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
            }
            mapped.order(ByteOrder.LITTLE_ENDIAN)
            return ParquetFile(mapped, ParquetFooterParser.parse(mapped), file.name)
        }

        /** For tests and for bytes that were just verified in memory. */
        fun wrap(bytes: ByteArray, source: String = "bytes"): ParquetFile {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            return ParquetFile(buf, ParquetFooterParser.parse(buf), source)
        }
    }
}

/** Decodes one column chunk (all its pages) into a [ColumnValues] of exactly `numRows` slots. */
private class ColumnChunkDecoder(private val file: ByteBuffer, private val source: String) {

    private class PageHeader(
        val type: Int,
        val uncompressedSize: Int,
        val compressedSize: Int,
        val numValues: Int,
        val encoding: ParquetEncoding,
        val definitionLevelEncoding: ParquetEncoding,
        val repetitionLevelEncoding: ParquetEncoding,
        val headerLength: Int,
    )

    private companion object {
        const val DATA_PAGE = 0
        const val INDEX_PAGE = 1
        const val DICTIONARY_PAGE = 2
        const val DATA_PAGE_V2 = 3
    }

    fun decode(leaf: LeafColumn, chunk: ColumnChunkMeta, numRows: Long): ColumnValues {
        if (numRows > Int.MAX_VALUE) throw ParquetFormatException("row group too large ($numRows rows)")
        val rows = numRows.toInt()
        if (chunk.numValues != numRows) throw ParquetFormatException("'${leaf.name}': ${chunk.numValues} values for $numRows rows — repeated columns are not supported ($source)")
        val width = leaf.type.plainWidth
        if (width < 0) throw ParquetFormatException("physical type ${leaf.type} of '${leaf.name}' is not supported ($source)")
        when (chunk.codec) {
            ParquetCodec.UNCOMPRESSED, ParquetCodec.LZ4_RAW -> Unit
            else -> throw ParquetFormatException("codec ${chunk.codec} of '${leaf.name}' is not supported ($source)")
        }
        if (chunk.dictionaryPageOffset != null && chunk.dictionaryPageOffset > 0) {
            throw ParquetFormatException("'${leaf.name}' has a dictionary page: dictionary encoding is not supported ($source)")
        }

        val valid: BooleanArray? = if (leaf.maxDefinitionLevel == 0) null else BooleanArray(rows)
        val ints = if (leaf.type == ParquetType.INT32) IntArray(rows) else null
        val longs = if (leaf.type == ParquetType.INT64) LongArray(rows) else null
        val floats = if (leaf.type == ParquetType.FLOAT) FloatArray(rows) else null

        var offset = chunk.firstPageOffset
        var row = 0
        var pages = 0
        while (row < rows) {
            if (offset < 0 || offset >= file.limit()) throw ParquetFormatException("'${leaf.name}': page offset $offset outside the file ($source)")
            val header = readPageHeader(offset, leaf.name)
            val dataStart = offset + header.headerLength
            val dataEnd = dataStart + header.compressedSize
            if (dataEnd > file.limit()) throw ParquetFormatException("'${leaf.name}': page at $offset runs past the end of the file ($source)")
            when (header.type) {
                DATA_PAGE -> {
                    if (header.encoding != ParquetEncoding.PLAIN) throw ParquetFormatException("'${leaf.name}': encoding ${header.encoding} is not supported, only PLAIN ($source)")
                    if (header.numValues > rows - row) throw ParquetFormatException("'${leaf.name}': page holds ${header.numValues} values but only ${rows - row} rows remain ($source)")
                    val page = pageBytes(chunk.codec, dataStart, header)
                    var pos = 0
                    val defined: Int
                    if (valid != null) {
                        if (header.definitionLevelEncoding != ParquetEncoding.RLE) throw ParquetFormatException("'${leaf.name}': definition levels use ${header.definitionLevelEncoding}, only RLE is supported ($source)")
                        val levelsLength = page.getInt(pos)
                        pos += 4
                        if (levelsLength < 0 || pos + levelsLength > page.limit()) throw ParquetFormatException("'${leaf.name}': definition-level block of $levelsLength bytes does not fit the page ($source)")
                        val levels = page.duplicate().apply { position(pos); limit(pos + levelsLength) }.slice().order(ByteOrder.LITTLE_ENDIAN)
                        defined = RleBitPackedHybrid.decodeBooleanLevels(levels, header.numValues, valid, row)
                        pos += levelsLength
                    } else {
                        defined = header.numValues
                    }
                    if (pos + defined * width > page.limit()) throw ParquetFormatException("'${leaf.name}': $defined PLAIN values do not fit the page ($source)")
                    val values = page.duplicate().apply { position(pos) }.order(ByteOrder.LITTLE_ENDIAN)
                    // Scatter the dense PLAIN values into their row slots (nulls keep a zero slot).
                    var k = 0
                    for (i in row until row + header.numValues) {
                        if (valid != null && !valid[i]) continue
                        when {
                            ints != null -> ints[i] = values.getInt(pos + k * 4)
                            longs != null -> longs[i] = values.getLong(pos + k * 8)
                            floats != null -> floats[i] = values.getFloat(pos + k * 4)
                        }
                        k++
                    }
                    row += header.numValues
                    pages++
                }
                DICTIONARY_PAGE -> throw ParquetFormatException("'${leaf.name}' has a dictionary page: dictionary encoding is not supported ($source)")
                DATA_PAGE_V2 -> throw ParquetFormatException("'${leaf.name}' uses Data Page V2, only V1 is supported ($source)")
                INDEX_PAGE -> Unit
                else -> throw ParquetFormatException("'${leaf.name}': unknown page type ${header.type} ($source)")
            }
            offset = dataEnd
            if (pages > 100_000) throw ParquetFormatException("'${leaf.name}': runaway page loop ($source)")
        }
        val validOrNull = valid?.takeIf { v -> v.any { !it } }
        return when {
            ints != null -> ColumnValues.Int32(ints, validOrNull)
            longs != null -> ColumnValues.Int64(longs, validOrNull)
            else -> ColumnValues.Float32(floats!!, validOrNull)
        }
    }

    private fun pageBytes(codec: ParquetCodec, start: Long, header: PageHeader): ByteBuffer {
        val raw = file.duplicate().apply { position(start.toInt()); limit((start + header.compressedSize).toInt()) }.slice().order(ByteOrder.LITTLE_ENDIAN)
        return when (codec) {
            ParquetCodec.UNCOMPRESSED -> {
                if (header.compressedSize != header.uncompressedSize) throw ParquetFormatException("uncompressed page sizes disagree (${header.compressedSize} vs ${header.uncompressedSize}) ($source)")
                raw
            }
            ParquetCodec.LZ4_RAW -> ByteBuffer.wrap(Lz4Block.decompress(raw, header.uncompressedSize)).order(ByteOrder.LITTLE_ENDIAN)
            else -> throw ParquetFormatException("codec $codec is not supported ($source)")
        }
    }

    private fun readPageHeader(offset: Long, column: String): PageHeader {
        val slice = file.duplicate().apply { position(offset.toInt()) }.slice().order(ByteOrder.LITTLE_ENDIAN)
        val r = ThriftCompactReader(slice)
        var type = -1
        var uncompressed = 0
        var compressed = 0
        var numValues = 0
        var encoding = ParquetEncoding.UNKNOWN
        var defEnc = ParquetEncoding.UNKNOWN
        var repEnc = ParquetEncoding.UNKNOWN
        try {
            r.readStruct { f ->
                when (f.id) {
                    1 -> type = r.readI32()
                    2 -> uncompressed = r.readI32()
                    3 -> compressed = r.readI32()
                    5 -> r.readStruct { d ->
                        when (d.id) {
                            1 -> numValues = r.readI32()
                            2 -> encoding = ParquetEncoding.of(r.readI32())
                            3 -> defEnc = ParquetEncoding.of(r.readI32())
                            4 -> repEnc = ParquetEncoding.of(r.readI32())
                            else -> r.skip(d.type)
                        }
                    }
                    else -> r.skip(f.type)
                }
            }
        } catch (e: RuntimeException) {
            throw ParquetFormatException("'$column': malformed page header at $offset: ${e.message} ($source)", e)
        }
        if (compressed < 0 || uncompressed < 0) throw ParquetFormatException("'$column': negative page size at $offset ($source)")
        return PageHeader(type, uncompressed, compressed, numValues, encoding, defEnc, repEnc, r.position)
    }
}

/**
 * The RLE / bit-packed hybrid used for definition levels. Only bit width 1 is needed (max
 * definition level 1 on a flat schema) but the decoder is written for any width ≤ 32.
 */
internal object RleBitPackedHybrid {

    /**
     * Decodes [count] levels from [data] into `valid[offset + i] = level == 1`.
     * @return the number of defined (level 1) values.
     */
    fun decodeBooleanLevels(data: ByteBuffer, count: Int, valid: BooleanArray, offset: Int): Int {
        val levels = decode(data, count, bitWidth = 1)
        var defined = 0
        for (i in 0 until count) {
            val v = levels[i] == 1
            valid[offset + i] = v
            if (v) defined++
        }
        return defined
    }

    fun decode(data: ByteBuffer, count: Int, bitWidth: Int): IntArray {
        require(bitWidth in 1..32) { "bit width $bitWidth" }
        val out = IntArray(count)
        val r = ThriftCompactReader(data)
        val byteWidth = (bitWidth + 7) / 8
        var n = 0
        while (n < count) {
            if (!data.hasRemaining()) throw ParquetFormatException("definition levels end after $n of $count values")
            val header = r.readVarint64()
            if (header and 1L == 0L) {
                // RLE run: count = header >> 1, then the value in byteWidth little-endian bytes.
                val run = (header ushr 1).toInt()
                var value = 0
                for (b in 0 until byteWidth) value = value or ((data.get().toInt() and 0xFF) shl (8 * b))
                if (run < 0 || n + run > count) throw ParquetFormatException("definition-level run of $run exceeds $count values")
                java.util.Arrays.fill(out, n, n + run, value)
                n += run
            } else {
                // Bit-packed: header >> 1 groups of 8 values, LSB first; the last group may pad past count.
                val groups = (header ushr 1).toInt()
                val mask = if (bitWidth == 32) -1 else (1 shl bitWidth) - 1
                var bitBuffer = 0L
                var bits = 0
                for (g in 0 until groups) {
                    for (v in 0 until 8) {
                        while (bits < bitWidth) {
                            if (!data.hasRemaining()) throw ParquetFormatException("bit-packed definition levels truncated")
                            bitBuffer = bitBuffer or ((data.get().toLong() and 0xFF) shl bits)
                            bits += 8
                        }
                        val value = (bitBuffer and mask.toLong()).toInt()
                        bitBuffer = bitBuffer ushr bitWidth
                        bits -= bitWidth
                        if (n < count) out[n++] = value
                    }
                }
            }
        }
        return out
    }
}
