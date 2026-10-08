package earth.walkthru.aqsync.data.history.parquet

/**
 * Reads the Parquet subset the CoreS3 firmware writes, plus what GeoParquet 2.0 files from PyArrow,
 * DuckDB and GDAL need (docs/parquet.md), and nothing else, on purpose:
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
 * refused loudly rather than misread. Bytes come from a [ByteReader] (a memory-mapped file from [open],
 * an array from [wrap]); only the requested column chunks are read and they decode straight into
 * primitive arrays ([ColumnValues]). A chart that needs three of 77 columns touches three column chunks.
 * [SuspendParquetFile] is the same reader over a suspending range source.
 *
 * Concurrent reads of the *same* instance are fine when its [ByteReader] is (the built-in ones are).
 */
class ParquetFile private constructor(
    private val reader: ByteReader,
    val metadata: ParquetFileMetadata,
    /** Where the bytes came from, for messages. */
    val source: String,
    codecs: ParquetCodecs,
) {
    private val chunks = ColumnChunks(metadata, source, codecs, reader.size)

    val leaves: List<LeafColumn> get() = metadata.leaves
    val numRows: Long get() = metadata.numRows
    val rowGroupCount: Int get() = metadata.rowGroups.size

    fun leaf(name: String): LeafColumn = chunks.leaf(name)

    /** One column across every row group, in row order. */
    fun readColumn(name: String): ColumnValues = readColumn(leaf(name))

    fun readColumn(leaf: LeafColumn): ColumnValues {
        if (metadata.rowGroups.isEmpty()) return chunks.empty(leaf)
        return ColumnValues.concat(metadata.rowGroups.indices.map { readColumn(leaf, it) })
    }

    /** Several columns at once; the map keeps the requested order. */
    fun readColumns(names: List<String>): Map<String, ColumnValues> = names.associateWith { readColumn(it) }

    /** One column of one row group, by name. */
    fun readColumn(name: String, rowGroup: Int): ColumnValues = readColumn(leaf(name), rowGroup)

    /** One column of one row group. */
    fun readColumn(leaf: LeafColumn, rowGroup: Int): ColumnValues {
        val range = chunks.range(leaf, rowGroup)
        return chunks.decode(leaf, rowGroup, range, readRange(range.start, range.length)) {
            chunks.widen(range).let { wide -> wide to readRange(wide.start, wide.length) }
        }
    }

    private fun readRange(start: Long, length: Int): ByteArray = try {
        reader.read(start, length)
    } catch (e: IndexOutOfBoundsException) {
        throw ParquetFormatException("bytes [$start, ${start + length}) outside the file ($source)", e)
    }

    /** The geometry columns of a GeoParquet file (2.0 native types and/or the `geo` key); empty for a plain file. */
    val geometryColumns: List<GeometryColumn> get() = metadata.geometryColumns

    /** Decodes one geometry column (all row groups) from WKB; null rows stay null. */
    fun readGeometry(column: GeometryColumn): List<Geometry?> = Wkb.decodeColumn(chunks.binary(readColumn(column.leaf), column.name))

    /** Decodes one geometry column of one row group. */
    fun readGeometry(column: GeometryColumn, rowGroup: Int): List<Geometry?> = Wkb.decodeColumn(chunks.binary(readColumn(column.leaf, rowGroup), column.name))

    companion object {
        /**
         * Maps [file] read-only (`FileChannel.map` on the JVM, `NSData` mapped on iOS) and parses its
         * footer. The mapping outlives any file handle.
         */
        fun open(file: LocalFile, codecs: ParquetCodecs = ParquetCodecs.builtIn): ParquetFile =
            open(file.mapReadOnly(), file.displayName, codecs)

        /** Opens the file behind any [ByteReader]: reads the footer now, column chunks on demand. */
        fun open(reader: ByteReader, source: String = "bytes", codecs: ParquetCodecs = ParquetCodecs.builtIn): ParquetFile =
            ParquetFile(reader, ParquetFooterParser.parse(reader), source, codecs)

        /** For tests and for bytes that were just verified in memory. */
        fun wrap(bytes: ByteArray, source: String = "bytes", codecs: ParquetCodecs = ParquetCodecs.builtIn): ParquetFile =
            ParquetFile(ByteArrayReader(bytes), ParquetFooterParser.parse(bytes), source, codecs)
    }
}

/**
 * [ParquetFile] over a [SuspendByteReader]: the footer is fetched first from the tail of the file (one
 * read of [open]'s `tailBytes` covers it in the common case, a second read fetches the rest), then each
 * call reads exactly the column chunks it decodes. Use [ParquetFileMetadata.rowGroupsOverlapping] to skip
 * row groups before reading. The head `PAR1` is checked only when the tail read reaches the start of the
 * file, so a large remote file costs no extra request.
 */
class SuspendParquetFile private constructor(
    private val reader: SuspendByteReader,
    val metadata: ParquetFileMetadata,
    val source: String,
    /** Total size of the file in bytes. */
    val size: Long,
    codecs: ParquetCodecs,
) {
    private val chunks = ColumnChunks(metadata, source, codecs, size)

    val leaves: List<LeafColumn> get() = metadata.leaves
    val numRows: Long get() = metadata.numRows
    val rowGroupCount: Int get() = metadata.rowGroups.size

    fun leaf(name: String): LeafColumn = chunks.leaf(name)

    /** One column of one row group, by name. */
    suspend fun readColumn(name: String, rowGroup: Int): ColumnValues = readColumn(leaf(name), rowGroup)

    /** One column of one row group: one range read of that column chunk. */
    suspend fun readColumn(leaf: LeafColumn, rowGroup: Int): ColumnValues {
        val range = chunks.range(leaf, rowGroup)
        return chunks.decode(leaf, rowGroup, range, readRange(range.start, range.length)) {
            chunks.widen(range).let { wide -> wide to readRange(wide.start, wide.length) }
        }
    }

    /** One column across [rowGroups] (default all), concatenated in the given order. */
    suspend fun readColumn(leaf: LeafColumn, rowGroups: List<Int> = metadata.rowGroups.indices.toList()): ColumnValues {
        if (rowGroups.isEmpty()) return chunks.empty(leaf)
        return ColumnValues.concat(rowGroups.map { readColumn(leaf, it) })
    }

    /** Several columns of one row group; the map keeps the requested order. */
    suspend fun readColumns(names: List<String>, rowGroup: Int): Map<String, ColumnValues> {
        val out = LinkedHashMap<String, ColumnValues>()
        for (name in names) out[name] = readColumn(name, rowGroup)
        return out
    }

    private suspend fun readRange(start: Long, length: Int): ByteArray = try {
        reader.read(start, length)
    } catch (e: IndexOutOfBoundsException) {
        throw ParquetFormatException("bytes [$start, ${start + length}) outside the file ($source)", e)
    }

    companion object {
        /** Default tail read: holds the whole footer of every file the app and the public feed write. */
        const val DEFAULT_TAIL_BYTES: Int = 64 * 1024

        suspend fun open(
            reader: SuspendByteReader,
            source: String = "remote",
            codecs: ParquetCodecs = ParquetCodecs.builtIn,
            tailBytes: Int = DEFAULT_TAIL_BYTES,
        ): SuspendParquetFile {
            val size = reader.size()
            if (size < 12) throw ParquetFormatException("file too small ($size bytes) ($source)")
            val tailLength = minOf(size, maxOf(tailBytes, 8).toLong()).toInt()
            val tailStart = size - tailLength
            val tail = reader.read(tailStart, tailLength)
            if (tailStart == 0L && !tail.copyOfRange(0, 4).contentEquals(ParquetFooterParser.MAGIC)) throw ParquetFormatException("PAR1 magic missing ($source)")
            val footerLength = ParquetFooterParser.footerLength(tail.copyOfRange(tailLength - 8, tailLength), size)
            val footerStart = size - 8 - footerLength
            val metadata = if (footerStart >= tailStart) {
                ParquetFooterParser.parseFooter(tail, (footerStart - tailStart).toInt(), footerLength)
            } else {
                // The footer is longer than the first read: fetch only the missing head of it.
                val missing = reader.read(footerStart, (tailStart - footerStart).toInt())
                ParquetFooterParser.parseFooter(missing + tail.copyOfRange(0, tailLength - 8), 0, footerLength)
            }
            return SuspendParquetFile(reader, metadata, source, size, codecs)
        }
    }
}

/** Byte range of one column chunk in the file. */
internal class ChunkRange(val start: Long, val length: Int)

/**
 * What [ParquetFile] and [SuspendParquetFile] share: where a column chunk lives and how its bytes decode.
 * Storage stays outside, so the same decoding serves a mapped file, an array and a remote range source.
 */
internal class ColumnChunks(
    val metadata: ParquetFileMetadata,
    val source: String,
    val codecs: ParquetCodecs,
    fileSize: Long,
) {
    /** End of the column data: where the footer starts. */
    val dataEnd: Long = fileSize - 8 - metadata.footerLength

    fun leaf(name: String): LeafColumn = metadata.leaf(name) ?: throw ParquetFormatException("no column '$name' in $source")

    fun chunk(leaf: LeafColumn, rowGroup: Int): Pair<RowGroupMeta, ColumnChunkMeta> {
        val rg = metadata.rowGroups.getOrNull(rowGroup) ?: throw ParquetFormatException("row group $rowGroup does not exist in $source")
        val chunk = rg.columns.getOrNull(leaf.index) ?: throw ParquetFormatException("no column chunk ${leaf.index} in row group $rowGroup ($source)")
        if (chunk.name != leaf.name) throw ParquetFormatException("column chunk ${leaf.index} is '${chunk.name}', expected '${leaf.name}' ($source)")
        return rg to chunk
    }

    /**
     * `[first page, first page + total_compressed_size)`, the bytes every page header and page of the chunk
     * lie in. A size that is missing or runs into the footer falls back to the end of the column data.
     */
    fun range(leaf: LeafColumn, rowGroup: Int): ChunkRange {
        val (_, chunk) = chunk(leaf, rowGroup)
        val start = chunk.firstPageOffset
        if (start < 0 || start >= dataEnd) throw ParquetFormatException("'${leaf.name}': page offset $start outside the file ($source)")
        val declared = chunk.totalCompressedSize
        val end = if (declared > 0 && start + declared <= dataEnd) start + declared else dataEnd
        return checked(leaf, start, end)
    }

    /** The same chunk, read up to the footer: for writers whose `total_compressed_size` undercounts. */
    fun widen(range: ChunkRange): ChunkRange = ChunkRange(range.start, (dataEnd - range.start).toInt())

    private fun checked(leaf: LeafColumn, start: Long, end: Long): ChunkRange {
        if (end - start > Int.MAX_VALUE) throw ParquetFormatException("'${leaf.name}': column chunk larger than 2 GiB ($source)")
        return ChunkRange(start, (end - start).toInt())
    }

    /**
     * Decodes [bytes] (the chunk at [range]). When a page runs past the end of [bytes] but not past the
     * column data, [wider] supplies a longer read once.
     */
    inline fun decode(leaf: LeafColumn, rowGroup: Int, range: ChunkRange, bytes: ByteArray, wider: () -> Pair<ChunkRange, ByteArray>): ColumnValues {
        val (rg, chunk) = chunk(leaf, rowGroup)
        val first = ColumnChunkDecoder(ByteCursor(bytes), range.start, dataEnd, source, codecs)
        return try {
            first.decode(leaf, chunk, rg.numRows)
        } catch (e: ColumnChunkDecoder.WindowTooShort) {
            if (range.start + range.length >= dataEnd) throw ParquetFormatException("'${leaf.name}': page at ${e.offset} runs past the end of the file ($source)")
            val (wide, more) = wider()
            ColumnChunkDecoder(ByteCursor(more), wide.start, dataEnd, source, codecs).decode(leaf, chunk, rg.numRows)
        }
    }

    fun empty(leaf: LeafColumn): ColumnValues = when (leaf.type) {
        ParquetType.INT32 -> ColumnValues.Int32(IntArray(0), null)
        ParquetType.INT64 -> ColumnValues.Int64(LongArray(0), null)
        ParquetType.FLOAT -> ColumnValues.Float32(FloatArray(0), null)
        ParquetType.DOUBLE -> ColumnValues.Float64(DoubleArray(0), null)
        ParquetType.BYTE_ARRAY -> ColumnValues.Binary(IntArray(1), ByteArray(0), null)
        else -> throw ParquetFormatException("physical type ${leaf.type} of '${leaf.name}' is not supported ($source)")
    }

    fun binary(values: ColumnValues, name: String): ColumnValues.Binary =
        values as? ColumnValues.Binary ?: throw ParquetFormatException("'$name' is not a BYTE_ARRAY column ($source)")
}

/**
 * Decodes one column chunk (all its pages) into a [ColumnValues] of exactly `numRows` slots.
 * [chunk] holds the file bytes from file offset [chunkStart]; [dataEnd] is where the footer starts.
 */
internal class ColumnChunkDecoder(
    private val chunk: ByteCursor,
    private val chunkStart: Long,
    private val dataEnd: Long,
    private val source: String,
    private val codecs: ParquetCodecs,
) {
    /** A page lies past the bytes this decoder was given (but maybe inside the file). */
    class WindowTooShort(val offset: Long) : RuntimeException()

    private val chunkEnd: Long = chunkStart + chunk.size

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
                    for ((i, ix) in indices.withIndex()) data.copyInto(bytes, out[i], offsets[ix], offsets[ix + 1])
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
                    dense.data.copyInto(buffer, byteLength, 0, pageBytes)
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

    /** Array index in [chunk] of file offset [offset]. */
    private fun at(offset: Long): Int = chunk.start + (offset - chunkStart).toInt()

    /** A window over file bytes `[offset, offset + length)`, which must lie in the chunk. */
    private fun fileWindow(offset: Long, length: Int): ByteCursor = chunk.window(at(offset), length)

    fun decode(leaf: LeafColumn, chunkMeta: ColumnChunkMeta, numRows: Long): ColumnValues {
        if (numRows > Int.MAX_VALUE) throw ParquetFormatException("row group too large ($numRows rows)")
        val rows = numRows.toInt()
        if (chunkMeta.numValues != numRows) throw ParquetFormatException("'${leaf.name}': ${chunkMeta.numValues} values for $numRows rows; repeated columns are not supported ($source)")
        when (leaf.type) {
            ParquetType.INT32, ParquetType.INT64, ParquetType.FLOAT, ParquetType.DOUBLE, ParquetType.BYTE_ARRAY -> Unit
            else -> throw ParquetFormatException("physical type ${leaf.type} of '${leaf.name}' is not supported ($source)")
        }
        val decompressor = codecs[chunkMeta.codec] ?: throw ParquetFormatException("codec ${chunkMeta.codec} of '${leaf.name}' is not supported (registered: ${codecs.supported}) ($source)")

        val valid: BooleanArray? = if (leaf.maxDefinitionLevel == 0) null else BooleanArray(rows)
        val builder = Builder(leaf.type, rows)
        var dictionary: Dense? = null

        var offset = chunkMeta.firstPageOffset
        var row = 0
        var pages = 0
        while (row < rows) {
            if (offset < 0 || offset >= dataEnd) throw ParquetFormatException("'${leaf.name}': page offset $offset outside the file ($source)")
            if (offset < chunkStart || offset >= chunkEnd) throw WindowTooShort(offset)
            val header = readPageHeader(offset, leaf.name)
            val dataStart = offset + header.headerLength
            val dataEnd = dataStart + header.compressedSize
            if (dataEnd > this.dataEnd) throw ParquetFormatException("'${leaf.name}': page at $offset runs past the end of the file ($source)")
            if (dataEnd > chunkEnd) throw WindowTooShort(offset)
            when (header.type) {
                DICTIONARY_PAGE -> {
                    if (dictionary != null) throw ParquetFormatException("'${leaf.name}': second dictionary page ($source)")
                    if (header.encoding != ParquetEncoding.PLAIN && header.encoding != ParquetEncoding.PLAIN_DICTIONARY) throw ParquetFormatException("'${leaf.name}': dictionary page encoding ${header.encoding} is not supported ($source)")
                    val page = decompress(decompressor, chunkMeta.codec, dataStart, header.compressedSize, header.uncompressedSize, leaf.name)
                    dictionary = decodePlain(leaf.type, page, 0, header.numValues, leaf.name)
                }
                DATA_PAGE -> {
                    if (header.numValues > rows - row) throw ParquetFormatException("'${leaf.name}': page holds ${header.numValues} values but only ${rows - row} rows remain ($source)")
                    val page = decompress(decompressor, chunkMeta.codec, dataStart, header.compressedSize, header.uncompressedSize, leaf.name)
                    var pos = 0
                    val defined: Int
                    if (valid != null) {
                        if (header.definitionLevelEncoding != ParquetEncoding.RLE) throw ParquetFormatException("'${leaf.name}': definition levels use ${header.definitionLevelEncoding}, only RLE is supported ($source)")
                        if (page.size < 4) throw ParquetFormatException("'${leaf.name}': page too small for definition levels ($source)")
                        val levelsLength = page.intAt(page.start + pos)
                        pos += 4
                        if (levelsLength < 0 || pos + levelsLength > page.size) throw ParquetFormatException("'${leaf.name}': definition-level block of $levelsLength bytes does not fit the page ($source)")
                        defined = RleBitPackedHybrid.decodeBooleanLevels(page.window(page.start + pos, levelsLength), header.numValues, valid, row)
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
                        defined = RleBitPackedHybrid.decodeBooleanLevels(fileWindow(dataStart, defLen), header.numValues, valid, row)
                        if (defined != header.numValues - header.numNulls) throw ParquetFormatException("'${leaf.name}': page declares ${header.numNulls} nulls but the levels show ${header.numValues - defined} ($source)")
                    } else {
                        if (header.numNulls != 0) throw ParquetFormatException("'${leaf.name}': required column with ${header.numNulls} nulls ($source)")
                        defined = header.numValues
                    }
                    val valuesStart = dataStart + defLen
                    val valuesCompressed = header.compressedSize - defLen
                    val valuesUncompressed = header.uncompressedSize - defLen
                    val values = if (header.isCompressed) {
                        decompress(decompressor, chunkMeta.codec, valuesStart, valuesCompressed, valuesUncompressed, leaf.name)
                    } else {
                        fileWindow(valuesStart, valuesCompressed)
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

    private fun decodeValues(leaf: LeafColumn, encoding: ParquetEncoding, page: ByteCursor, pos: Int, defined: Int, dictionary: Dense?): Dense = when (encoding) {
        ParquetEncoding.PLAIN -> decodePlain(leaf.type, page, pos, defined, leaf.name)
        ParquetEncoding.RLE_DICTIONARY, ParquetEncoding.PLAIN_DICTIONARY -> {
            val dict = dictionary ?: throw ParquetFormatException("'${leaf.name}': dictionary-encoded page without a dictionary page ($source)")
            if (defined == 0) {
                dict.gather(IntArray(0), leaf.name, source)
            } else {
                if (pos >= page.size) throw ParquetFormatException("'${leaf.name}': dictionary indices missing ($source)")
                val bitWidth = page.data[page.start + pos].toInt() and 0xFF
                if (bitWidth > 32) throw ParquetFormatException("'${leaf.name}': dictionary index bit width $bitWidth ($source)")
                val indices = RleBitPackedHybrid.decode(page.window(page.start + pos + 1, page.size - pos - 1), defined, bitWidth)
                dict.gather(indices, leaf.name, source)
            }
        }
        else -> throw ParquetFormatException("'${leaf.name}': encoding $encoding is not supported, only PLAIN and dictionary ($source)")
    }

    /** PLAIN: fixed-width little-endian values, or `i32 length + bytes` for BYTE_ARRAY. [pos] is relative to the page. */
    private fun decodePlain(type: ParquetType, page: ByteCursor, pos: Int, count: Int, column: String): Dense {
        val base = page.start
        val limit = page.size
        if (count < 0) throw ParquetFormatException("'$column': negative value count ($source)")
        if (type == ParquetType.BYTE_ARRAY) {
            val offsets = IntArray(count + 1)
            var at = pos
            var total = 0
            for (i in 0 until count) {
                if (at + 4 > limit) throw ParquetFormatException("'$column': BYTE_ARRAY page truncated at value $i ($source)")
                val len = page.intAt(base + at)
                if (len < 0 || at.toLong() + 4 + len > limit) throw ParquetFormatException("'$column': BYTE_ARRAY length $len does not fit the page ($source)")
                offsets[i] = total
                total += len
                at += 4 + len
            }
            offsets[count] = total
            val data = ByteArray(total)
            at = pos
            for (i in 0 until count) {
                val len = offsets[i + 1] - offsets[i]
                page.data.copyInto(data, offsets[i], base + at + 4, base + at + 4 + len)
                at += 4 + len
            }
            return Dense.Bin(offsets, data)
        }
        val width = type.plainWidth
        if (pos + count.toLong() * width > limit) throw ParquetFormatException("'$column': $count PLAIN values do not fit the page ($source)")
        val b = page.data
        val o = base + pos
        return when (type) {
            ParquetType.INT32 -> Dense.I32(IntArray(count) { leInt(b, o + it * 4) })
            ParquetType.INT64 -> Dense.I64(LongArray(count) { leLong(b, o + it * 8) })
            ParquetType.FLOAT -> Dense.F32(FloatArray(count) { Float.fromBits(leInt(b, o + it * 4)) })
            ParquetType.DOUBLE -> Dense.F64(DoubleArray(count) { Double.fromBits(leLong(b, o + it * 8)) })
            else -> throw ParquetFormatException("physical type $type of '$column' is not supported ($source)")
        }
    }

    private fun decompress(decompressor: PageDecompressor, codec: ParquetCodec, start: Long, compressedSize: Int, uncompressedSize: Int, column: String): ByteCursor {
        if (compressedSize < 0 || uncompressedSize < 0) throw ParquetFormatException("'$column': negative page size ($source)")
        val raw = fileWindow(start, compressedSize)
        if (codec == ParquetCodec.UNCOMPRESSED) {
            if (compressedSize != uncompressedSize) throw ParquetFormatException("'$column': uncompressed page sizes disagree ($compressedSize vs $uncompressedSize) ($source)")
            return raw
        }
        val input = raw.data.copyOfRange(raw.start, raw.limit)
        val out = try {
            decompressor.decompress(input, uncompressedSize)
        } catch (e: ParquetFormatException) {
            throw ParquetFormatException("'$column': ${e.message} ($source)", e)
        } catch (e: RuntimeException) {
            throw ParquetFormatException("'$column': $codec page failed to decompress: ${e.message} ($source)", e)
        }
        if (out.size != uncompressedSize) throw ParquetFormatException("'$column': $codec page produced ${out.size} bytes, header says $uncompressedSize ($source)")
        return ByteCursor(out)
    }

    private fun readPageHeader(offset: Long, column: String): PageHeader {
        val r = ThriftCompactReader(chunk.window(at(offset), (chunkEnd - offset).toInt()))
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
        } catch (e: IndexOutOfBoundsException) {
            // The header itself runs past the bytes we hold: a wider read may complete it.
            if (chunkEnd < dataEnd) throw WindowTooShort(offset)
            throw ParquetFormatException("'$column': malformed page header at $offset: ${e.message} ($source)", e)
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
    fun decodeBooleanLevels(data: ByteCursor, count: Int, valid: BooleanArray, offset: Int): Int {
        val levels = decode(data, count, bitWidth = 1)
        var defined = 0
        for (i in 0 until count) {
            val v = levels[i] == 1
            valid[offset + i] = v
            if (v) defined++
        }
        return defined
    }

    fun decode(data: ByteCursor, count: Int, bitWidth: Int): IntArray {
        if (bitWidth !in 0..32) throw ParquetFormatException("bit width $bitWidth")
        val out = IntArray(count)
        if (count == 0) return out
        val r = ThriftCompactReader(data)
        val byteWidth = (bitWidth + 7) / 8
        var n = 0
        while (n < count) {
            if (!data.hasRemaining) throw ParquetFormatException("RLE/bit-packed data ends after $n of $count values")
            val header = r.readVarint64()
            if (header and 1L == 0L) {
                // RLE run: count = header >> 1, then the value in byteWidth little-endian bytes.
                val run = (header ushr 1).toInt()
                var value = 0
                for (b in 0 until byteWidth) {
                    if (!data.hasRemaining) throw ParquetFormatException("RLE run value truncated")
                    value = value or (data.getU8() shl (8 * b))
                }
                if (run < 0 || n + run > count) throw ParquetFormatException("RLE run of $run exceeds $count values")
                out.fill(value, n, n + run)
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
                            if (!data.hasRemaining) throw ParquetFormatException("bit-packed data truncated")
                            bitBuffer = bitBuffer or (data.getU8().toLong() shl bits)
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
