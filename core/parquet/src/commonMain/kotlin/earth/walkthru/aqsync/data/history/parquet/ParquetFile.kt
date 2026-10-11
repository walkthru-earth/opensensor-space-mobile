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
 * refused loudly rather than misread. Bytes come from a [ByteReader] (a positional file reader from [open],
 * an array from [wrap]); only the requested column chunks are read and they decode straight into
 * primitive arrays ([ColumnValues]). A chart that needs three of 77 columns touches three column chunks.
 * [ParquetReader] is the suspending reader over a [RangeSource], with page-level pruning.
 *
 * Concurrent reads of the *same* instance are fine when its [ByteReader] is (the built-in ones are).
 * [close] closes the [ByteReader], so a builder that opens many files releases each descriptor when it
 * is done with it (#61).
 */
class ParquetFile private constructor(
    private val reader: ByteReader,
    val metadata: ParquetFileMetadata,
    /** Where the bytes came from, for messages. */
    val source: String,
    codecs: ParquetCodecs,
) : AutoCloseable {
    private val chunks = ColumnChunks(metadata, source, codecs, reader.size)

    val leaves: List<LeafColumn> get() = metadata.leaves
    val numRows: Long get() = metadata.numRows
    val rowGroupCount: Int get() = metadata.rowGroups.size

    fun leaf(name: String): LeafColumn = chunks.leaf(name)

    /** Closes the [ByteReader]. Reading a column afterwards throws [IllegalStateException]. */
    override fun close() = reader.close()

    /** One column across every row group, in row order. */
    fun readColumn(name: String): ColumnValues = readColumn(leaf(name))

    fun readColumn(leaf: LeafColumn): ColumnValues {
        // Every row group decodes into one sink sized for the whole file, so nothing is concatenated.
        val sink = chunks.sink(leaf, metadata.numRows, metadata.rowGroups.indices)
        val scratch = DecodeScratch()
        for (rowGroup in metadata.rowGroups.indices) decodeInto(sink, scratch, leaf, rowGroup)
        return sink.build().toColumnValues()
    }

    /** Several columns at once; the map keeps the requested order. */
    fun readColumns(names: List<String>): Map<String, ColumnValues> = names.associateWith { readColumn(it) }

    /** One column of one row group, by name. */
    fun readColumn(name: String, rowGroup: Int): ColumnValues = readColumn(leaf(name), rowGroup)

    /** One column of one row group. */
    fun readColumn(leaf: LeafColumn, rowGroup: Int): ColumnValues {
        val sink = chunks.sink(leaf, chunks.chunk(leaf, rowGroup).first.numRows, rowGroup..rowGroup)
        decodeInto(sink, DecodeScratch(), leaf, rowGroup)
        return sink.build().toColumnValues()
    }

    private fun decodeInto(sink: ColumnSink, scratch: DecodeScratch, leaf: LeafColumn, rowGroup: Int) {
        val range = chunks.range(leaf, rowGroup)
        chunks.decode(sink, scratch, leaf, rowGroup, range, readRange(range.start, range.length)) {
            chunks.widen(range).let { wide -> wide to readRange(wide.start, wide.length) }
        }
    }

    private fun readRange(start: Long, length: Int): ByteView = try {
        reader.view(start, length)
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
         * Opens [file] for positional reads (`FileChannel` on the JVM, `pread` on iOS, no mapping) and
         * parses its footer. The file holds one descriptor until [close].
         */
        fun open(file: LocalFile, codecs: ParquetCodecs = ParquetCodecs.builtIn): ParquetFile =
            open(file.openReader(), file.displayName, codecs)

        /**
         * Opens the file behind any [ByteReader]: reads the footer now, column chunks on demand. The
         * file owns [reader] from here, and a footer that does not parse closes it before throwing.
         */
        fun open(reader: ByteReader, source: String = "bytes", codecs: ParquetCodecs = ParquetCodecs.builtIn): ParquetFile {
            val metadata = try {
                ParquetFooterParser.parse(reader)
            } catch (e: Throwable) {
                reader.close()
                throw e
            }
            return ParquetFile(reader, metadata, source, codecs)
        }

        /** For tests and for bytes that were just verified in memory. */
        fun wrap(bytes: ByteArray, source: String = "bytes", codecs: ParquetCodecs = ParquetCodecs.builtIn): ParquetFile =
            ParquetFile(ByteArrayReader(bytes), ParquetFooterParser.parse(bytes), source, codecs)
    }
}

/** Byte range of one column chunk in the file. */
internal class ChunkRange(val start: Long, val length: Int)

/**
 * What [ParquetFile] and [ParquetReader] share: where a column chunk lives and how its bytes decode.
 * Storage stays outside, so the same decoding serves a local file, an array and a remote range source.
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
     * Decodes [bytes] (the chunk at [range]) into [sink]. When a page runs past the end of [bytes] but not
     * past the column data, [wider] supplies a longer read once and the chunk decodes again from the start.
     */
    inline fun decode(
        sink: ColumnSink,
        scratch: DecodeScratch,
        leaf: LeafColumn,
        rowGroup: Int,
        range: ChunkRange,
        bytes: ByteView,
        wider: () -> Pair<ChunkRange, ByteView>,
    ): Int {
        val (rg, chunk) = chunk(leaf, rowGroup)
        val mark = sink.mark()
        val first = ChunkDecoder(leaf, chunk, rg.numRows, null, sink, scratch, codecs, dataEnd, source)
        return try {
            first.decodeChunk(bytes, range.start)
            first.pages
        } catch (e: ChunkDecoder.WindowTooShort) {
            if (range.start + range.length >= dataEnd) throw ParquetFormatException("'${leaf.name}': page at ${e.offset} runs past the end of the file ($source)")
            sink.reset(mark)
            val (wide, more) = wider()
            val second = ChunkDecoder(leaf, chunk, rg.numRows, null, sink, scratch, codecs, dataEnd, source)
            try {
                second.decodeChunk(more, wide.start)
            } catch (e: ChunkDecoder.WindowTooShort) {
                throw ParquetFormatException("'${leaf.name}': page at ${e.offset} runs past the end of the file ($source)")
            }
            second.pages
        }
    }

    /**
     * A sink for [rows] rows of [leaf] over [rowGroups]. The row count comes from the footer, so it is
     * capped at [ParquetLimits.MAX_ROWS] before any slot is allocated. BYTE_ARRAY bytes start at the
     * uncompressed size the footer declares, capped so a hostile footer cannot ask for a huge array up front.
     */
    fun sink(leaf: LeafColumn, rows: Long, rowGroups: Iterable<Int>): ColumnSink {
        if (rows !in 0..ParquetLimits.MAX_ROWS) throw ParquetFormatException("'${leaf.name}': $rows rows exceed the limit of ${ParquetLimits.MAX_ROWS} per column ($source)")
        var hint = 0L
        if (leaf.type == ParquetType.BYTE_ARRAY) for (rg in rowGroups) hint += chunk(leaf, rg).second.totalUncompressedSize.coerceAtLeast(0)
        return ColumnSink(leaf, rows.toInt(), hint.coerceAtMost(MAX_BINARY_HINT).toInt(), source)
    }

    fun binary(values: ColumnValues, name: String): ColumnValues.Binary =
        values as? ColumnValues.Binary ?: throw ParquetFormatException("'$name' is not a BYTE_ARRAY column ($source)")

    private companion object {
        const val MAX_BINARY_HINT = 16L * 1024 * 1024
    }
}
