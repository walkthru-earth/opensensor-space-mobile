package earth.walkthru.aqsync.data.history.parquet

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Reads the Parquet subset the CoreS3 firmware writes, plus what GeoParquet 2.0 files from PyArrow,
 * DuckDB and GDAL need (docs/history-ux-and-data-layer.md §7) — and nothing else, on purpose:
 *
 * - flat schema, leaves of physical type INT32 / INT64 / FLOAT / DOUBLE / BYTE_ARRAY, REQUIRED or
 *   OPTIONAL (max definition level 1, no repetition levels);
 * - any number of row groups, Data Page V1 or V2, PLAIN values or a PLAIN dictionary page with
 *   RLE_DICTIONARY / PLAIN_DICTIONARY indices, RLE/bit-packed definition levels;
 * - codecs from a [ParquetCodecs] registry: UNCOMPRESSED, LZ4_RAW, SNAPPY, GZIP built in, ZSTD when the
 *   caller registers one;
 * - footer statistics, the TIMESTAMP / STRING / GEOMETRY / GEOGRAPHY logical types and the geospatial
 *   statistics are exposed, not required.
 *
 * Anything else raises [ParquetFormatException] naming the feature, so a file from another writer is
 * refused loudly rather than misread. Files are memory-mapped and decoded straight into primitive
 * arrays ([ColumnValues]); a chart that needs three of 77 columns touches three column chunks.
 *
 * Concurrent reads of the *same* instance are fine (absolute reads, duplicated buffers) — the mapped
 * buffer itself is never repositioned.
 */
class ParquetFile private constructor(
    private val buf: ByteBuffer,
    val metadata: ParquetFileMetadata,
    /** Where the bytes came from, for messages. */
    val source: String,
    private val codecs: ParquetCodecs,
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
        return ColumnChunkDecoder(buf, source, codecs).decode(leaf, chunk, rg.numRows)
    }

    /** The geometry columns of a GeoParquet file (2.0 native types and/or the `geo` key); empty for a plain file. */
    val geometryColumns: List<GeometryColumn> get() = metadata.geometryColumns

    /** Decodes one geometry column (all row groups) from WKB; null rows stay null. */
    fun readGeometry(column: GeometryColumn): List<Geometry?> = Wkb.decodeColumn(binary(readColumn(column.leaf), column.name))

    /** Decodes one geometry column of one row group. */
    fun readGeometry(column: GeometryColumn, rowGroup: Int): List<Geometry?> = Wkb.decodeColumn(binary(readColumn(column.leaf, rowGroup), column.name))

    private fun binary(values: ColumnValues, name: String): ColumnValues.Binary =
        values as? ColumnValues.Binary ?: throw ParquetFormatException("'$name' is not a BYTE_ARRAY column ($source)")

    private fun empty(leaf: LeafColumn): ColumnValues = when (leaf.type) {
        ParquetType.INT32 -> ColumnValues.Int32(IntArray(0), null)
        ParquetType.INT64 -> ColumnValues.Int64(LongArray(0), null)
        ParquetType.FLOAT -> ColumnValues.Float32(FloatArray(0), null)
        ParquetType.DOUBLE -> ColumnValues.Float64(DoubleArray(0), null)
        ParquetType.BYTE_ARRAY -> ColumnValues.Binary(IntArray(1), ByteArray(0), null)
        else -> throw ParquetFormatException("physical type ${leaf.type} of '${leaf.name}' is not supported ($source)")
    }

    companion object {
        /** Memory-maps [file] read-only and parses its footer. The mapping outlives the channel. */
        fun open(file: File, codecs: ParquetCodecs = ParquetCodecs.builtIn): ParquetFile {
            val mapped = RandomAccessFile(file, "r").use { raf ->
                raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
            }
            mapped.order(ByteOrder.LITTLE_ENDIAN)
            return ParquetFile(mapped, ParquetFooterParser.parse(mapped), file.name, codecs)
        }

        /** For tests and for bytes that were just verified in memory. */
        fun wrap(bytes: ByteArray, source: String = "bytes", codecs: ParquetCodecs = ParquetCodecs.builtIn): ParquetFile {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            return ParquetFile(buf, ParquetFooterParser.parse(buf), source, codecs)
        }
    }
}

/** Decodes one column chunk (all its pages) into a [ColumnValues] of exactly `numRows` slots. */
private class ColumnChunkDecoder(private val file: ByteBuffer, private val source: String, private val codecs: ParquetCodecs) {

    private class PageHeader(
        val type: Int,
        val uncompressedSize: Int,
        val compressedSize: Int,
        val numValues: Int,
        val encoding: ParquetEncoding,
        val definitionLevelEncoding: ParquetEncoding,
        val headerLength: Int,
        // Data Page V2 only:
        val numNulls: Int = 0,
        val definitionLevelsByteLength: Int = 0,
        val repetitionLevelsByteLength: Int = 0,
        val isCompressed: Boolean = true,
    )

    /** The PLAIN values of a page or a dictionary, dense (nulls removed), before they are scattered into row slots. */
    private sealed class Dense {
        abstract val count: Int

        class I32(val v: IntArray) : Dense() { override val count get() = v.size }
        class I64(val v: LongArray) : Dense() { override val count get() = v.size }
        class F32(val v: FloatArray) : Dense() { override val count get() = v.size }
        class F64(val v: DoubleArray) : Dense() { override val count get() = v.size }
        class Bin(val offsets: IntArray, val data: ByteArray) : Dense() { override val count get() = offsets.size - 1 }

        /** Dictionary lookup: `indices` into this, producing the values in index order. */
        fun gather(indices: IntArray, column: String, source: String): Dense {
            for (ix in indices) if (ix < 0 || ix >= count) throw ParquetFormatException("'$column': dictionary index $ix outside a dictionary of $count ($source)")
            return when (this) {
                is I32 -> I32(IntArray(indices.size) { v[indices[it]] })
                is I64 -> I64(LongArray(indices.size) { v[indices[it]] })
                is F32 -> F32(FloatArray(indices.size) { v[indices[it]] })
                is F64 -> F64(DoubleArray(indices.size) { v[indices[it]] })
                is Bin -> {
                    val out = IntArray(indices.size + 1)
                    var total = 0
                    for ((i, ix) in indices.withIndex()) { out[i] = total; total += offsets[ix + 1] - offsets[ix] }
                    out[indices.size] = total
                    val bytes = ByteArray(total)
                    for ((i, ix) in indices.withIndex()) System.arraycopy(data, offsets[ix], bytes, out[i], offsets[ix + 1] - offsets[ix])
                    Bin(out, bytes)
                }
            }
        }
    }

    /** Accumulates the rows of one chunk; fixed-width types preallocate, BYTE_ARRAY grows. */
    private class Builder(type: ParquetType, private val rows: Int) {
        private val ints = if (type == ParquetType.INT32) IntArray(rows) else null
        private val longs = if (type == ParquetType.INT64) LongArray(rows) else null
        private val floats = if (type == ParquetType.FLOAT) FloatArray(rows) else null
        private val doubles = if (type == ParquetType.DOUBLE) DoubleArray(rows) else null
        private val offsets = if (type == ParquetType.BYTE_ARRAY) IntArray(rows + 1) else null
        private var bytes = if (type == ParquetType.BYTE_ARRAY) ByteArray(0) else null
        private var byteLength = 0

        /** Scatters [dense] over rows `[row, row + numValues)`, skipping the null slots of [valid]. */
        fun scatter(dense: Dense, valid: BooleanArray?, row: Int, numValues: Int) {
            var k = 0
            when (dense) {
                is Dense.I32 -> for (i in row until row + numValues) { if (valid == null || valid[i]) ints!![i] = dense.v[k++] }
                is Dense.I64 -> for (i in row until row + numValues) { if (valid == null || valid[i]) longs!![i] = dense.v[k++] }
                is Dense.F32 -> for (i in row until row + numValues) { if (valid == null || valid[i]) floats!![i] = dense.v[k++] }
                is Dense.F64 -> for (i in row until row + numValues) { if (valid == null || valid[i]) doubles!![i] = dense.v[k++] }
                is Dense.Bin -> {
                    val pageBytes = dense.offsets.last()
                    val need = byteLength + pageBytes
                    var buffer = bytes!!
                    if (need > buffer.size) { buffer = buffer.copyOf(maxOf(need, buffer.size * 2)); bytes = buffer }
                    System.arraycopy(dense.data, 0, buffer, byteLength, pageBytes)
                    val offs = offsets!!
                    for (i in row until row + numValues) {
                        offs[i] = byteLength + dense.offsets[k]
                        if (valid == null || valid[i]) k++
                    }
                    byteLength += pageBytes
                    offs[row + numValues] = byteLength
                }
            }
        }

        fun build(valid: BooleanArray?): ColumnValues = when {
            ints != null -> ColumnValues.Int32(ints, valid)
            longs != null -> ColumnValues.Int64(longs, valid)
            floats != null -> ColumnValues.Float32(floats, valid)
            doubles != null -> ColumnValues.Float64(doubles, valid)
            else -> { offsets!![rows] = byteLength; ColumnValues.Binary(offsets, bytes!!.copyOf(byteLength), valid) }
        }
    }

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
        when (leaf.type) {
            ParquetType.INT32, ParquetType.INT64, ParquetType.FLOAT, ParquetType.DOUBLE, ParquetType.BYTE_ARRAY -> Unit
            else -> throw ParquetFormatException("physical type ${leaf.type} of '${leaf.name}' is not supported ($source)")
        }
        val decompressor = codecs[chunk.codec] ?: throw ParquetFormatException("codec ${chunk.codec} of '${leaf.name}' is not supported (registered: ${codecs.supported}) ($source)")

        val valid: BooleanArray? = if (leaf.maxDefinitionLevel == 0) null else BooleanArray(rows)
        val builder = Builder(leaf.type, rows)
        var dictionary: Dense? = null

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
                DICTIONARY_PAGE -> {
                    if (dictionary != null) throw ParquetFormatException("'${leaf.name}': second dictionary page ($source)")
                    if (header.encoding != ParquetEncoding.PLAIN && header.encoding != ParquetEncoding.PLAIN_DICTIONARY) throw ParquetFormatException("'${leaf.name}': dictionary page encoding ${header.encoding} is not supported ($source)")
                    val page = decompress(decompressor, chunk.codec, dataStart, header.compressedSize, header.uncompressedSize, leaf.name)
                    dictionary = decodePlain(leaf.type, page, 0, header.numValues, leaf.name)
                }
                DATA_PAGE -> {
                    if (header.numValues > rows - row) throw ParquetFormatException("'${leaf.name}': page holds ${header.numValues} values but only ${rows - row} rows remain ($source)")
                    val page = decompress(decompressor, chunk.codec, dataStart, header.compressedSize, header.uncompressedSize, leaf.name)
                    var pos = 0
                    val defined: Int
                    if (valid != null) {
                        if (header.definitionLevelEncoding != ParquetEncoding.RLE) throw ParquetFormatException("'${leaf.name}': definition levels use ${header.definitionLevelEncoding}, only RLE is supported ($source)")
                        if (page.limit() < 4) throw ParquetFormatException("'${leaf.name}': page too small for definition levels ($source)")
                        val levelsLength = page.getInt(pos)
                        pos += 4
                        if (levelsLength < 0 || pos + levelsLength > page.limit()) throw ParquetFormatException("'${leaf.name}': definition-level block of $levelsLength bytes does not fit the page ($source)")
                        defined = RleBitPackedHybrid.decodeBooleanLevels(slice(page, pos, levelsLength), header.numValues, valid, row)
                        pos += levelsLength
                    } else {
                        defined = header.numValues
                    }
                    val dense = decodeValues(leaf, header.encoding, page, pos, defined, dictionary)
                    builder.scatter(dense, valid, row, header.numValues)
                    row += header.numValues
                    pages++
                }
                DATA_PAGE_V2 -> {
                    if (header.numValues > rows - row) throw ParquetFormatException("'${leaf.name}': page holds ${header.numValues} values but only ${rows - row} rows remain ($source)")
                    if (header.repetitionLevelsByteLength != 0) throw ParquetFormatException("'${leaf.name}': repetition levels are not supported ($source)")
                    val defLen = header.definitionLevelsByteLength
                    if (defLen < 0 || defLen > header.compressedSize) throw ParquetFormatException("'${leaf.name}': definition-level block of $defLen bytes does not fit the page ($source)")
                    val defined: Int
                    if (valid != null) {
                        if (defLen == 0) throw ParquetFormatException("'${leaf.name}': optional column without definition levels ($source)")
                        defined = RleBitPackedHybrid.decodeBooleanLevels(slice(file, dataStart.toInt(), defLen), header.numValues, valid, row)
                        if (defined != header.numValues - header.numNulls) throw ParquetFormatException("'${leaf.name}': page declares ${header.numNulls} nulls but the levels show ${header.numValues - defined} ($source)")
                    } else {
                        if (header.numNulls != 0) throw ParquetFormatException("'${leaf.name}': required column with ${header.numNulls} nulls ($source)")
                        defined = header.numValues
                    }
                    val valuesStart = dataStart + defLen
                    val valuesCompressed = header.compressedSize - defLen
                    val valuesUncompressed = header.uncompressedSize - defLen
                    val values = if (header.isCompressed) {
                        decompress(decompressor, chunk.codec, valuesStart, valuesCompressed, valuesUncompressed, leaf.name)
                    } else {
                        slice(file, valuesStart.toInt(), valuesCompressed)
                    }
                    val dense = decodeValues(leaf, header.encoding, values, 0, defined, dictionary)
                    builder.scatter(dense, valid, row, header.numValues)
                    row += header.numValues
                    pages++
                }
                INDEX_PAGE -> Unit
                else -> throw ParquetFormatException("'${leaf.name}': unknown page type ${header.type} ($source)")
            }
            offset = dataEnd
            if (pages > 100_000) throw ParquetFormatException("'${leaf.name}': runaway page loop ($source)")
        }
        val validOrNull = valid?.takeIf { v -> v.any { !it } }
        return builder.build(validOrNull)
    }

    private fun decodeValues(leaf: LeafColumn, encoding: ParquetEncoding, page: ByteBuffer, pos: Int, defined: Int, dictionary: Dense?): Dense = when (encoding) {
        ParquetEncoding.PLAIN -> decodePlain(leaf.type, page, pos, defined, leaf.name)
        ParquetEncoding.RLE_DICTIONARY, ParquetEncoding.PLAIN_DICTIONARY -> {
            val dict = dictionary ?: throw ParquetFormatException("'${leaf.name}': dictionary-encoded page without a dictionary page ($source)")
            if (defined == 0) {
                dict.gather(IntArray(0), leaf.name, source)
            } else {
                if (pos >= page.limit()) throw ParquetFormatException("'${leaf.name}': dictionary indices missing ($source)")
                val bitWidth = page.get(pos).toInt() and 0xFF
                if (bitWidth > 32) throw ParquetFormatException("'${leaf.name}': dictionary index bit width $bitWidth ($source)")
                val indices = RleBitPackedHybrid.decode(slice(page, pos + 1, page.limit() - pos - 1), defined, bitWidth)
                dict.gather(indices, leaf.name, source)
            }
        }
        else -> throw ParquetFormatException("'${leaf.name}': encoding $encoding is not supported, only PLAIN and dictionary ($source)")
    }

    /** PLAIN: fixed-width little-endian values, or `i32 length + bytes` for BYTE_ARRAY. */
    private fun decodePlain(type: ParquetType, page: ByteBuffer, pos: Int, count: Int, column: String): Dense {
        val p = page.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        if (type == ParquetType.BYTE_ARRAY) {
            val offsets = IntArray(count + 1)
            var at = pos
            var total = 0
            for (i in 0 until count) {
                if (at + 4 > p.limit()) throw ParquetFormatException("'$column': BYTE_ARRAY page truncated at value $i ($source)")
                val len = p.getInt(at)
                if (len < 0 || at + 4 + len > p.limit()) throw ParquetFormatException("'$column': BYTE_ARRAY length $len does not fit the page ($source)")
                offsets[i] = total
                total += len
                at += 4 + len
            }
            offsets[count] = total
            val data = ByteArray(total)
            at = pos
            for (i in 0 until count) {
                val len = offsets[i + 1] - offsets[i]
                p.position(at + 4)
                p.get(data, offsets[i], len)
                at += 4 + len
            }
            return Dense.Bin(offsets, data)
        }
        val width = type.plainWidth
        if (pos + count.toLong() * width > p.limit()) throw ParquetFormatException("'$column': $count PLAIN values do not fit the page ($source)")
        return when (type) {
            ParquetType.INT32 -> Dense.I32(IntArray(count) { p.getInt(pos + it * 4) })
            ParquetType.INT64 -> Dense.I64(LongArray(count) { p.getLong(pos + it * 8) })
            ParquetType.FLOAT -> Dense.F32(FloatArray(count) { p.getFloat(pos + it * 4) })
            ParquetType.DOUBLE -> Dense.F64(DoubleArray(count) { p.getDouble(pos + it * 8) })
            else -> throw ParquetFormatException("physical type $type of '$column' is not supported ($source)")
        }
    }

    private fun decompress(decompressor: PageDecompressor, codec: ParquetCodec, start: Long, compressedSize: Int, uncompressedSize: Int, column: String): ByteBuffer {
        if (compressedSize < 0 || uncompressedSize < 0) throw ParquetFormatException("'$column': negative page size ($source)")
        val raw = slice(file, start.toInt(), compressedSize)
        if (codec == ParquetCodec.UNCOMPRESSED) {
            if (compressedSize != uncompressedSize) throw ParquetFormatException("'$column': uncompressed page sizes disagree ($compressedSize vs $uncompressedSize) ($source)")
            return raw
        }
        val out = try {
            decompressor.decompress(raw, uncompressedSize)
        } catch (e: ParquetFormatException) {
            throw ParquetFormatException("'$column': ${e.message} ($source)", e)
        } catch (e: RuntimeException) {
            throw ParquetFormatException("'$column': $codec page failed to decompress: ${e.message} ($source)", e)
        }
        if (out.size != uncompressedSize) throw ParquetFormatException("'$column': $codec page produced ${out.size} bytes, header says $uncompressedSize ($source)")
        return ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
    }

    private fun slice(buf: ByteBuffer, start: Int, length: Int): ByteBuffer {
        if (start < 0 || length < 0 || start + length > buf.limit()) throw ParquetFormatException("slice [$start, ${start + length}) outside ${buf.limit()} bytes ($source)")
        return buf.duplicate().apply { position(start); limit(start + length) }.slice().order(ByteOrder.LITTLE_ENDIAN)
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
        var numNulls = 0
        var defLen = 0
        var repLen = 0
        var isCompressed = true
        try {
            r.readStruct { f ->
                when (f.id) {
                    1 -> type = r.readI32()
                    2 -> uncompressed = r.readI32()
                    3 -> compressed = r.readI32()
                    5 -> r.readStruct { d ->
                        // DataPageHeader
                        when (d.id) {
                            1 -> numValues = r.readI32()
                            2 -> encoding = ParquetEncoding.of(r.readI32())
                            3 -> defEnc = ParquetEncoding.of(r.readI32())
                            else -> r.skip(d.type)
                        }
                    }
                    7 -> r.readStruct { d ->
                        // DictionaryPageHeader
                        when (d.id) {
                            1 -> numValues = r.readI32()
                            2 -> encoding = ParquetEncoding.of(r.readI32())
                            else -> r.skip(d.type)
                        }
                    }
                    8 -> r.readStruct { d ->
                        // DataPageHeaderV2
                        when (d.id) {
                            1 -> numValues = r.readI32()
                            2 -> numNulls = r.readI32()
                            3 -> r.readI32() // num_rows: equals num_values on a flat column
                            4 -> encoding = ParquetEncoding.of(r.readI32())
                            5 -> defLen = r.readI32()
                            6 -> repLen = r.readI32()
                            7 -> isCompressed = r.booleanFromHeader(d)
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
        return PageHeader(type, uncompressed, compressed, numValues, encoding, defEnc, r.position, numNulls, defLen, repLen, isCompressed)
    }
}

/**
 * The RLE / bit-packed hybrid used for definition levels and dictionary indices. Written for any bit
 * width 0..32 (0 = every value is 0, which a one-entry dictionary produces).
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
        if (bitWidth !in 0..32) throw ParquetFormatException("bit width $bitWidth")
        val out = IntArray(count)
        if (count == 0) return out
        val r = ThriftCompactReader(data)
        val byteWidth = (bitWidth + 7) / 8
        var n = 0
        while (n < count) {
            if (!data.hasRemaining()) throw ParquetFormatException("RLE/bit-packed data ends after $n of $count values")
            val header = r.readVarint64()
            if (header and 1L == 0L) {
                // RLE run: count = header >> 1, then the value in byteWidth little-endian bytes.
                val run = (header ushr 1).toInt()
                var value = 0
                for (b in 0 until byteWidth) {
                    if (!data.hasRemaining()) throw ParquetFormatException("RLE run value truncated")
                    value = value or ((data.get().toInt() and 0xFF) shl (8 * b))
                }
                if (run < 0 || n + run > count) throw ParquetFormatException("RLE run of $run exceeds $count values")
                java.util.Arrays.fill(out, n, n + run, value)
                n += run
            } else {
                // Bit-packed: header >> 1 groups of 8 values, LSB first; the last group may pad past count.
                val groups = (header ushr 1).toInt()
                val mask = (1L shl bitWidth) - 1 // bitWidth 32 → 0xFFFFFFFF
                var bitBuffer = 0L
                var bits = 0
                for (g in 0 until groups) {
                    for (v in 0 until 8) {
                        while (bits < bitWidth) {
                            if (!data.hasRemaining()) throw ParquetFormatException("bit-packed data truncated")
                            bitBuffer = bitBuffer or ((data.get().toLong() and 0xFF) shl bits)
                            bits += 8
                        }
                        val value = (bitBuffer and mask).toInt()
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
