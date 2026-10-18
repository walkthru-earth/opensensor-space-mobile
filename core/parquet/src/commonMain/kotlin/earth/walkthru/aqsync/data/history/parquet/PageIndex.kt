package earth.walkthru.aqsync.data.history.parquet

/**
 * The `OffsetIndex` of one column chunk: where each data page starts, its size including the page
 * header, and the row group row its first value belongs to. A reader that knows which rows it wants
 * reads only the pages that hold them.
 */
class OffsetIndex(
    val offsets: LongArray,
    /** Compressed page size including the header, so page `i` is `offsets[i] until offsets[i] + sizes[i]`. */
    val sizes: IntArray,
    val firstRows: LongArray,
) {
    val pageCount: Int get() = offsets.size

    fun pageRange(page: Int): LongRange = byteRange(offsets[page], sizes[page])

    /** Rows `[firstRows[page], end)` of [page] in a row group of [rowGroupRows] rows. */
    fun pageRowEnd(page: Int, rowGroupRows: Long): Long = if (page + 1 < pageCount) firstRows[page + 1] else rowGroupRows

    /**
     * Checks this index against its chunk: pages in ascending, non-overlapping order inside [chunk],
     * the first page at row 0 and every page starting before [rowGroupRows]. A reader then never asks
     * for bytes outside the chunk, however the index was written.
     */
    fun checkWithin(chunk: LongRange, rowGroupRows: Long) {
        if (pageCount == 0) return
        if (firstRows[0] != 0L) throw ParquetFormatException("offset index starts at row ${firstRows[0]}, not 0")
        var end = chunk.first
        for (i in 0 until pageCount) {
            if (offsets[i] < end || offsets[i] > chunk.last || sizes[i] > chunk.last - offsets[i] + 1) throw ParquetFormatException("offset index page $i at ${offsets[i]} (${sizes[i]} bytes) overlaps or leaves its chunk $chunk")
            if (firstRows[i] >= rowGroupRows) throw ParquetFormatException("offset index page $i starts at row ${firstRows[i]} of $rowGroupRows")
            end = offsets[i] + sizes[i]
        }
    }

    companion object {
        fun parse(view: ByteView): OffsetIndex {
            val r = ThriftCompactReader(view.cursor())
            var offsets = LongArray(0)
            var sizes = IntArray(0)
            var firstRows = LongArray(0)
            try {
                r.readStruct { f ->
                    when (f.id) {
                        1 -> {
                            // The size is capped at the bytes left in the index, so a corrupt count cannot over-allocate.
                            val list = r.readListHeader(f, ThriftCompactReader.Type.STRUCT)
                            offsets = LongArray(list.size)
                            sizes = IntArray(list.size)
                            firstRows = LongArray(list.size)
                            for (i in 0 until list.size) {
                                r.readStruct { p ->
                                    when (p.id) {
                                        1 -> offsets[i] = r.readI64(p)
                                        2 -> sizes[i] = r.readI32(p)
                                        3 -> firstRows[i] = r.readI64(p)
                                        else -> r.skip(p.type)
                                    }
                                }
                            }
                        }
                        else -> r.skip(f.type)
                    }
                }
            } catch (e: RuntimeException) {
                throw ParquetFormatException("malformed offset index: ${e.message}", e)
            }
            for (i in offsets.indices) {
                if (offsets[i] < 0 || sizes[i] <= 0 || firstRows[i] < 0 || (i > 0 && firstRows[i] < firstRows[i - 1])) {
                    throw ParquetFormatException("offset index page $i is out of order or out of range")
                }
            }
            return OffsetIndex(offsets, sizes, firstRows)
        }
    }
}

/**
 * The `ColumnIndex` of one column chunk: per data page, whether every value is null and the page's
 * min and max in the column's PLAIN encoding. [minLong] and [maxLong] decode them for INT32 and INT64.
 */
class ColumnIndex(
    val nullPages: BooleanArray,
    val minValues: List<ByteArray>,
    val maxValues: List<ByteArray>,
    /** 0 unordered, 1 ascending, 2 descending. */
    val boundaryOrder: Int,
    val nullCounts: LongArray?,
) {
    val pageCount: Int get() = nullPages.size

    fun minLong(page: Int, type: ParquetType): Long? = if (nullPages[page]) null else decodeLong(minValues[page], type)

    fun maxLong(page: Int, type: ParquetType): Long? = if (nullPages[page]) null else decodeLong(maxValues[page], type)

    private fun decodeLong(bytes: ByteArray, type: ParquetType): Long? = when {
        type == ParquetType.INT64 && bytes.size == 8 -> leLong(bytes, 0)
        type == ParquetType.INT32 && bytes.size == 4 -> leInt(bytes, 0).toLong()
        else -> null
    }

    companion object {
        fun parse(view: ByteView): ColumnIndex {
            val r = ThriftCompactReader(view.cursor())
            var nullPages = BooleanArray(0)
            var mins: List<ByteArray> = emptyList()
            var maxs: List<ByteArray> = emptyList()
            var order = 0
            var nullCounts: LongArray? = null
            try {
                r.readStruct { f ->
                    when (f.id) {
                        1 -> {
                            // Writers tag a bool list with either boolean wire type.
                            r.expect(f, ThriftCompactReader.Type.LIST)
                            val list = r.readListHeader()
                            require(list.size == 0 || list.elementType == ThriftCompactReader.Type.BOOLEAN_TRUE || list.elementType == ThriftCompactReader.Type.BOOLEAN_FALSE) { "null_pages is not a bool list" }
                            nullPages = BooleanArray(list.size) { r.readListBoolean() }
                        }
                        2 -> mins = List(r.readListHeader(f, ThriftCompactReader.Type.BINARY).size) { r.readBinary() }
                        3 -> maxs = List(r.readListHeader(f, ThriftCompactReader.Type.BINARY).size) { r.readBinary() }
                        4 -> order = r.readI32(f)
                        5 -> {
                            val list = r.readListHeader(f, ThriftCompactReader.Type.I64)
                            nullCounts = LongArray(list.size) { r.readI64() }
                        }
                        else -> r.skip(f.type)
                    }
                }
            } catch (e: RuntimeException) {
                throw ParquetFormatException("malformed column index: ${e.message}", e)
            }
            if (mins.size != nullPages.size || maxs.size != nullPages.size || (nullCounts != null && nullCounts!!.size != nullPages.size)) {
                throw ParquetFormatException("column index lists disagree (${nullPages.size} pages, ${mins.size} mins, ${maxs.size} maxes)")
            }
            nullCounts?.forEachIndexed { i, n -> if (n < 0) throw ParquetFormatException("column index page $i declares $n nulls") }
            return ColumnIndex(nullPages, mins, maxs, order, nullCounts)
        }
    }
}

/**
 * Where one column chunk lives: its pages from the first page header to the end of the chunk, and its
 * page index when the writer wrote one. All three are file byte ranges a [RangeSource] reads.
 */
class ChunkLocation(
    val rowGroup: Int,
    val column: Int,
    val pages: LongRange,
    val offsetIndex: LongRange?,
    val columnIndex: LongRange?,
)

/**
 * The chunk of [leaf] in [rowGroup]: `[first page, first page + total_compressed_size)`, falling back to
 * the end of the column data when the size is missing or runs into the footer.
 */
fun ParquetFileMetadata.chunkLocation(rowGroup: Int, leaf: LeafColumn, fileSize: Long): ChunkLocation {
    val rg = rowGroups.getOrNull(rowGroup) ?: throw ParquetFormatException("row group $rowGroup does not exist")
    val chunk = rg.columns.getOrNull(leaf.index) ?: throw ParquetFormatException("no column chunk ${leaf.index} in row group $rowGroup")
    if (chunk.name != leaf.name) throw ParquetFormatException("column chunk ${leaf.index} is '${chunk.name}', expected '${leaf.name}'")
    val dataEnd = fileSize - 8 - footerLength
    val start = chunk.firstPageOffset
    if (start < 0 || start >= dataEnd) throw ParquetFormatException("'${leaf.name}': page offset $start outside the file")
    val end = chunkEnd(start, chunk.totalCompressedSize, dataEnd)
    if (end - start > Int.MAX_VALUE) throw ParquetFormatException("'${leaf.name}': column chunk larger than 2 GiB")
    fun inData(r: LongRange?) = r?.takeIf { it.first >= 0 && it.last < dataEnd }
    return ChunkLocation(rowGroup, leaf.index, start until end, inData(chunk.offsetIndex), inData(chunk.columnIndex))
}
