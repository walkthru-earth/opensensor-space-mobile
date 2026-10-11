package earth.walkthru.aqsync.data.history.parquet

/**
 * Rows of one row group to decode, as sorted disjoint `[starts[i], ends[i])` ranges. [ALL] decodes
 * every row. Pages that hold none of these rows need not be read at all.
 */
internal class RowSelection(val starts: LongArray, val ends: LongArray) {
    fun count(): Long {
        var n = 0L
        for (i in starts.indices) n += ends[i] - starts[i]
        return n
    }

    fun overlaps(from: Long, until: Long): Boolean {
        for (i in starts.indices) if (starts[i] < until && ends[i] > from) return true
        return false
    }

    companion object {
        /** Merges `[from, until)` pairs (in any order, overlapping or touching) into a selection. */
        fun of(from: LongArray, until: LongArray): RowSelection {
            val order = from.indices.filter { from[it] < until[it] }.sortedBy { from[it] }
            val starts = ArrayList<Long>()
            val ends = ArrayList<Long>()
            for (i in order) {
                if (ends.isNotEmpty() && from[i] <= ends.last()) {
                    ends[ends.size - 1] = maxOf(ends.last(), until[i])
                } else {
                    starts += from[i]
                    ends += until[i]
                }
            }
            return RowSelection(starts.toLongArray(), ends.toLongArray())
        }
    }
}

/**
 * Bounds on sizes a file declares before the reader allocates for them. Footers and page headers are
 * untrusted (a remote file is read over HTTP), and a few bytes can declare billions of rows or a 2 GiB
 * page. Row counts cannot be bounded by bytes, since one RLE run encodes any number of nulls, so they get
 * a fixed cap far above any file the app reads (device archives hold under 2,000 rows a file).
 */
internal object ParquetLimits {
    /** Rows of one column in one decode, 16 Mi, 128 MiB as INT64. */
    const val MAX_ROWS: Long = 1L shl 24

    /** Uncompressed bytes of one page. Writers default to 1 MiB pages and 1 MiB dictionaries. */
    const val MAX_PAGE_BYTES: Int = 64 * 1024 * 1024
}

/**
 * The output of one column across a scan: exactly [capacity] slots, filled in row order by one
 * [ChunkDecoder] per row group. Fixed-width arrays are allocated once at their final size; BYTE_ARRAY
 * bytes grow from [binaryHint].
 */
internal class ColumnSink(val leaf: LeafColumn, val capacity: Int, binaryHint: Int, private val source: String) {
    private val ints = if (leaf.type == ParquetType.INT32) IntArray(capacity) else null
    private val longs = if (leaf.type == ParquetType.INT64) LongArray(capacity) else null
    private val floats = if (leaf.type == ParquetType.FLOAT) FloatArray(capacity) else null
    private val doubles = if (leaf.type == ParquetType.DOUBLE) DoubleArray(capacity) else null
    private val offsets = if (leaf.type == ParquetType.BYTE_ARRAY) IntArray(capacity + 1) else null
    private var bytes = if (leaf.type == ParquetType.BYTE_ARRAY) ByteArray(maxOf(binaryHint, 16)) else null
    private var byteLength = 0
    private val validity = if (leaf.maxDefinitionLevel == 0) null else LongArray(PrimitiveColumn.words(capacity))

    /** Slots filled so far. */
    var count = 0
        private set

    init {
        when (leaf.type) {
            ParquetType.INT32, ParquetType.INT64, ParquetType.FLOAT, ParquetType.DOUBLE, ParquetType.BYTE_ARRAY -> Unit
            else -> throw ParquetFormatException("physical type ${leaf.type} of '${leaf.name}' is not supported ($source)")
        }
    }

    /** Where to come back to when a chunk must be decoded again from a wider read. */
    fun mark(): Long = (count.toLong() shl 32) or byteLength.toLong()

    fun reset(mark: Long) {
        val slot = (mark ushr 32).toInt()
        if (validity != null) for (i in slot until count) validity[i ushr 6] = validity[i ushr 6] and (1L shl (i and 63)).inv()
        count = slot
        byteLength = (mark and 0xFFFF_FFFFL).toInt()
    }

    private fun next(): Int {
        if (count >= capacity) throw ParquetFormatException("'${leaf.name}': more rows than the row groups declare ($source)")
        return count++
    }

    private fun setValid(slot: Int) {
        validity!![slot ushr 6] = validity[slot ushr 6] or (1L shl (slot and 63))
    }

    fun putNull() {
        val slot = next()
        offsets?.set(slot, byteLength)
    }

    /** A fixed-width PLAIN value at [at] of [b]. */
    fun putPlain(b: ByteArray, at: Int, optional: Boolean) {
        val slot = next()
        when {
            ints != null -> ints[slot] = leInt(b, at)
            longs != null -> longs[slot] = leLong(b, at)
            floats != null -> floats[slot] = Float.fromBits(leInt(b, at))
            else -> doubles!![slot] = Double.fromBits(leLong(b, at))
        }
        if (optional) setValid(slot)
    }

    fun putBytes(b: ByteArray, at: Int, length: Int, optional: Boolean) {
        val slot = next()
        val offs = offsets!!
        offs[slot] = byteLength
        val need = byteLength + length
        var buffer = bytes!!
        if (need > buffer.size) {
            buffer = buffer.copyOf(maxOf(need, buffer.size * 2))
            bytes = buffer
        }
        b.copyInto(buffer, byteLength, at, at + length)
        byteLength = need
        if (optional) setValid(slot)
    }

    fun build(): PrimitiveColumn {
        if (count != capacity) throw ParquetFormatException("'${leaf.name}': decoded $count of $capacity rows ($source)")
        val bits = validity?.takeIf { PrimitiveColumn.popCount(it, capacity) < capacity }
        return when {
            ints != null -> PrimitiveColumn.I32(ints, capacity, bits)
            longs != null -> PrimitiveColumn.I64(longs, capacity, bits)
            floats != null -> PrimitiveColumn.F32(floats, capacity, bits)
            doubles != null -> PrimitiveColumn.F64(doubles, capacity, bits)
            else -> {
                offsets!![capacity] = byteLength
                PrimitiveColumn.Bin(offsets, bytes!!, capacity, bits)
            }
        }
    }
}

/** Buffers one scan reuses across pages and chunks, so a page costs no allocation once they have grown. */
internal class DecodeScratch {
    var page = ByteArray(0)
    var dictionary = ByteArray(0)
    var dictionaryEntries = IntArray(0)
    val levels = RleDecoder()
    val indices = RleDecoder()

    fun page(size: Int): ByteArray {
        if (page.size < size) page = ByteArray(maxOf(size, page.size * 2))
        return page
    }

    fun dictionary(size: Int): ByteArray {
        if (dictionary.size < size) dictionary = ByteArray(maxOf(size, dictionary.size * 2))
        return dictionary
    }

    fun dictionaryEntries(size: Int): IntArray {
        if (dictionaryEntries.size < size) dictionaryEntries = IntArray(maxOf(size, dictionaryEntries.size * 2))
        return dictionaryEntries
    }
}

/**
 * Decodes the pages of one column chunk straight into a [ColumnSink], one pass per page: definition
 * levels, PLAIN values and dictionary indices are read as streams and each selected row is written to
 * its final slot. Compressed pages are decompressed from the fetched bytes into [scratch]; uncompressed
 * pages are read in place. Nothing is copied on the way except the values a BYTE_ARRAY column keeps.
 *
 * Supported: Data Page V1 and V2, PLAIN and PLAIN/RLE dictionary encodings, RLE definition levels with
 * max level 1, no repetition. Anything else raises [ParquetFormatException] naming the feature.
 */
internal class ChunkDecoder(
    private val leaf: LeafColumn,
    private val chunk: ColumnChunkMeta,
    /** Rows of the row group. */
    private val rows: Long,
    private val selection: RowSelection?,
    private val sink: ColumnSink,
    private val scratch: DecodeScratch,
    codecs: ParquetCodecs,
    /** Where the footer starts: no page may run past it. */
    private val dataEnd: Long,
    private val source: String,
) {
    /** A page lies past the bytes this decoder was given, but maybe inside the file. */
    class WindowTooShort(val offset: Long) : RuntimeException()

    private val decompressor = codecs[chunk.codec]
        ?: throw ParquetFormatException("codec ${chunk.codec} of '${leaf.name}' is not supported (registered: ${codecs.supported}) ($source)")
    private val optional = leaf.maxDefinitionLevel > 0
    private val width = if (leaf.type == ParquetType.BYTE_ARRAY) 0 else leaf.type.plainWidth

    /** No page is larger than its whole chunk claims to be, nor than [ParquetLimits.MAX_PAGE_BYTES]. */
    private val maxPageBytes: Int = chunk.totalUncompressedSize.let { if (it in 1 until ParquetLimits.MAX_PAGE_BYTES) it.toInt() else ParquetLimits.MAX_PAGE_BYTES }

    private var dictionary: ByteArray? = null
    private var dictionaryCount = 0
    private var selectionIndex = 0

    /** Pages decoded, data pages only. */
    var pages = 0
        private set

    init {
        if (chunk.numValues != rows) throw ParquetFormatException("'${leaf.name}': ${chunk.numValues} values for $rows rows; repeated columns are not supported ($source)")
    }

    /**
     * Decodes every page of the chunk from [view], the file bytes from [viewStart]. Throws [WindowTooShort]
     * when a page runs past [view] but not past the column data, so the caller can read more and retry.
     */
    fun decodeChunk(view: ByteView, viewStart: Long) {
        var offset = chunk.firstPageOffset
        var row = 0L
        var guard = 0
        while (row < rows) {
            row += decodePageAt(view, viewStart, offset, row) { next -> offset = next }
            if (++guard > MAX_PAGES) throw ParquetFormatException("'${leaf.name}': runaway page loop ($source)")
        }
    }

    /**
     * Decodes the pages the page index points at: [prefix] (the dictionary page, when the chunk has one,
     * from the first page offset up to the first data page) and each of [pages], one data page each,
     * whose first rows are [firstRows] and that hold [pageRows] rows by the offset index.
     */
    fun decodePages(prefix: ByteView?, prefixStart: Long, pages: List<ByteView>, pageStarts: LongArray, firstRows: LongArray, pageRows: LongArray) {
        if (prefix != null && prefix.length > 0) {
            var offset = prefixStart
            val end = prefixStart + prefix.length
            while (offset < end) {
                decodePageAt(prefix, prefixStart, offset, 0L, dictionaryOnly = true) { next -> offset = next }
            }
        }
        for (i in pages.indices) {
            val held = decodePageAt(pages[i], pageStarts[i], pageStarts[i], firstRows[i]) { }
            if (held != pageRows[i]) throw ParquetFormatException("'${leaf.name}': page at ${pageStarts[i]} holds $held rows, its offset index says ${pageRows[i]} ($source)")
        }
    }

    /** Decodes the page whose header is at file [offset]; returns the rows it held and reports where the next page starts. */
    private inline fun decodePageAt(view: ByteView, viewStart: Long, offset: Long, row: Long, dictionaryOnly: Boolean = false, next: (Long) -> Unit): Long {
        val viewEnd = viewStart + view.length
        if (offset < 0 || offset >= dataEnd) throw ParquetFormatException("'${leaf.name}': page offset $offset outside the file ($source)")
        if (offset < viewStart || offset >= viewEnd) throw WindowTooShort(offset)
        val base = view.offset - viewStart
        val header = readPageHeader(view.array, (base + offset).toInt(), (viewEnd - offset).toInt(), offset, viewEnd)
        if (header.uncompressedSize > maxPageBytes) throw ParquetFormatException("'${leaf.name}': page at $offset declares ${header.uncompressedSize} bytes, more than the $maxPageBytes its chunk allows ($source)")
        val bodyStart = offset + header.headerLength
        val bodyEnd = bodyStart + header.compressedSize
        if (bodyEnd > dataEnd) throw ParquetFormatException("'${leaf.name}': page at $offset runs past the end of the file ($source)")
        if (bodyEnd > viewEnd) throw WindowTooShort(offset)
        val at = (base + bodyStart).toInt()
        next(bodyEnd)
        return when (header.type) {
            DICTIONARY_PAGE -> {
                readDictionary(header, view.array, at)
                0L
            }
            DATA_PAGE, DATA_PAGE_V2 -> {
                if (dictionaryOnly) throw ParquetFormatException("'${leaf.name}': data page at $offset before the first indexed page ($source)")
                if (header.numValues > rows - row) throw ParquetFormatException("'${leaf.name}': page holds ${header.numValues} values but only ${rows - row} rows remain ($source)")
                if (header.type == DATA_PAGE) readDataPageV1(header, view.array, at, row) else readDataPageV2(header, view.array, at, row)
                pages++
                header.numValues.toLong()
            }
            INDEX_PAGE -> 0L
            else -> throw ParquetFormatException("'${leaf.name}': unknown page type ${header.type} ($source)")
        }
    }

    private fun readDictionary(header: PageHeader, b: ByteArray, at: Int) {
        if (dictionary != null) throw ParquetFormatException("'${leaf.name}': second dictionary page ($source)")
        if (header.encoding != ParquetEncoding.PLAIN && header.encoding != ParquetEncoding.PLAIN_DICTIONARY) throw ParquetFormatException("'${leaf.name}': dictionary page encoding ${header.encoding} is not supported ($source)")
        val size = header.uncompressedSize
        val bytes = scratch.dictionary(size)
        decompressInto(b, at, header.compressedSize, bytes, size)
        val count = header.numValues
        if (count < 0) throw ParquetFormatException("'${leaf.name}': negative dictionary size ($source)")
        if (width > 0) {
            if (count.toLong() * width > size) throw ParquetFormatException("'${leaf.name}': $count PLAIN values do not fit the page ($source)")
        } else {
            if (count.toLong() * 4 > size) throw ParquetFormatException("'${leaf.name}': $count BYTE_ARRAY values do not fit the page ($source)")
            val entries = scratch.dictionaryEntries(count)
            var pos = 0
            for (i in 0 until count) {
                if (pos + 4 > size) throw ParquetFormatException("'${leaf.name}': BYTE_ARRAY page truncated at value $i ($source)")
                val len = leInt(bytes, pos)
                if (len < 0 || pos.toLong() + 4 + len > size) throw ParquetFormatException("'${leaf.name}': BYTE_ARRAY length $len does not fit the page ($source)")
                entries[i] = pos
                pos += 4 + len
            }
        }
        dictionary = bytes
        dictionaryCount = count
    }

    private fun readDataPageV1(header: PageHeader, b: ByteArray, at: Int, row: Long) {
        val size = header.uncompressedSize
        val page: ByteArray
        val start: Int
        if (chunk.codec == ParquetCodec.UNCOMPRESSED) {
            if (header.compressedSize != size) throw ParquetFormatException("'${leaf.name}': uncompressed page sizes disagree (${header.compressedSize} vs $size) ($source)")
            page = b
            start = at
        } else {
            page = scratch.page(size)
            decompressInto(b, at, header.compressedSize, page, size)
            start = 0
        }
        var pos = start
        val limit = start + size
        var levels: RleDecoder? = null
        if (optional) {
            if (header.definitionLevelEncoding != ParquetEncoding.RLE) throw ParquetFormatException("'${leaf.name}': definition levels use ${header.definitionLevelEncoding}, only RLE is supported ($source)")
            if (size < 4) throw ParquetFormatException("'${leaf.name}': page too small for definition levels ($source)")
            val levelsLength = leInt(page, pos)
            pos += 4
            if (levelsLength < 0 || pos.toLong() + levelsLength > limit) throw ParquetFormatException("'${leaf.name}': definition-level block of $levelsLength bytes does not fit the page ($source)")
            levels = scratch.levels.reset(page, pos, pos + levelsLength, 1, leaf.name, source)
            pos += levelsLength
        }
        decodeValues(header, page, pos, limit, levels, row)
    }

    private fun readDataPageV2(header: PageHeader, b: ByteArray, at: Int, row: Long) {
        if (header.repetitionLevelsByteLength != 0) throw ParquetFormatException("'${leaf.name}': repetition levels are not supported ($source)")
        val defLen = header.definitionLevelsByteLength
        if (defLen < 0 || defLen > header.compressedSize) throw ParquetFormatException("'${leaf.name}': definition-level block of $defLen bytes does not fit the page ($source)")
        var levels: RleDecoder? = null
        if (optional) {
            if (defLen == 0) throw ParquetFormatException("'${leaf.name}': optional column without definition levels ($source)")
            levels = scratch.levels.reset(b, at, at + defLen, 1, leaf.name, source)
            // V2 states its null count up front; check it against the levels before decoding.
            val defined = scratch.levels.countOnes(header.numValues)
            if (defined != header.numValues - header.numNulls) throw ParquetFormatException("'${leaf.name}': page declares ${header.numNulls} nulls but the levels show ${header.numValues - defined} ($source)")
            levels = scratch.levels.reset(b, at, at + defLen, 1, leaf.name, source)
        } else if (header.numNulls != 0) {
            throw ParquetFormatException("'${leaf.name}': required column with ${header.numNulls} nulls ($source)")
        }
        val compressed = header.compressedSize - defLen
        val size = header.uncompressedSize - defLen
        if (size < 0) throw ParquetFormatException("'${leaf.name}': negative page size ($source)")
        if (!header.isCompressed || chunk.codec == ParquetCodec.UNCOMPRESSED) {
            if (chunk.codec == ParquetCodec.UNCOMPRESSED && compressed != size) throw ParquetFormatException("'${leaf.name}': uncompressed page sizes disagree ($compressed vs $size) ($source)")
            decodeValues(header, b, at + defLen, at + defLen + compressed, levels, row)
        } else {
            val page = scratch.page(size)
            decompressInto(b, at + defLen, compressed, page, size)
            decodeValues(header, page, 0, size, levels, row)
        }
    }

    /**
     * Walks the rows `[row, row + numValues)` of one page. Runs of selected rows are written to the sink,
     * runs outside the selection are skipped without decoding a value.
     */
    private fun decodeValues(header: PageHeader, page: ByteArray, start: Int, limit: Int, levels: RleDecoder?, row: Long) {
        val n = header.numValues
        val dictionaryEncoded = when (header.encoding) {
            ParquetEncoding.PLAIN -> false
            ParquetEncoding.RLE_DICTIONARY, ParquetEncoding.PLAIN_DICTIONARY -> true
            else -> throw ParquetFormatException("'${leaf.name}': encoding ${header.encoding} is not supported, only PLAIN and dictionary ($source)")
        }
        var indices: RleDecoder? = null
        if (dictionaryEncoded) {
            if (dictionary == null) throw ParquetFormatException("'${leaf.name}': dictionary-encoded page without a dictionary page ($source)")
            if (start < limit) {
                val bitWidth = page[start].toInt() and 0xFF
                if (bitWidth > 32) throw ParquetFormatException("'${leaf.name}': dictionary index bit width $bitWidth ($source)")
                indices = scratch.indices.reset(page, start + 1, limit, bitWidth, leaf.name, source)
            }
        }
        var pos = start
        var i = 0
        while (i < n) {
            val r = row + i
            val (selected, until) = runAt(r, row + n)
            val run = (until - r).toInt()
            for (k in 0 until run) {
                val defined = levels == null || levels.next() == 1
                if (!defined) {
                    if (selected) sink.putNull()
                    continue
                }
                if (dictionaryEncoded) {
                    val ix = (indices ?: throw ParquetFormatException("'${leaf.name}': dictionary indices missing ($source)")).next()
                    if (ix < 0 || ix >= dictionaryCount) throw ParquetFormatException("'${leaf.name}': dictionary index $ix outside a dictionary of $dictionaryCount ($source)")
                    if (selected) putDictionary(ix)
                } else if (width > 0) {
                    if (pos + width > limit) throw ParquetFormatException("'${leaf.name}': PLAIN values do not fit the page ($source)")
                    if (selected) sink.putPlain(page, pos, optional)
                    pos += width
                } else {
                    if (pos + 4 > limit) throw ParquetFormatException("'${leaf.name}': BYTE_ARRAY page truncated ($source)")
                    val len = leInt(page, pos)
                    if (len < 0 || pos.toLong() + 4 + len > limit) throw ParquetFormatException("'${leaf.name}': BYTE_ARRAY length $len does not fit the page ($source)")
                    if (selected) sink.putBytes(page, pos + 4, len, optional)
                    pos += 4 + len
                }
            }
            i += run
        }
    }

    private fun putDictionary(ix: Int) {
        val dict = dictionary!!
        if (width > 0) {
            sink.putPlain(dict, ix * width, optional)
        } else {
            val at = scratch.dictionaryEntries[ix]
            sink.putBytes(dict, at + 4, leInt(dict, at), optional)
        }
    }

    /** Whether row [r] is selected, and the row (at most [pageEnd]) where that changes. */
    private fun runAt(r: Long, pageEnd: Long): Pair<Boolean, Long> {
        val sel = selection ?: return true to pageEnd
        while (selectionIndex < sel.starts.size && sel.ends[selectionIndex] <= r) selectionIndex++
        if (selectionIndex == sel.starts.size) return false to pageEnd
        val s = sel.starts[selectionIndex]
        return if (r >= s) true to minOf(sel.ends[selectionIndex], pageEnd) else false to minOf(s, pageEnd)
    }

    private fun decompressInto(src: ByteArray, at: Int, compressedSize: Int, dst: ByteArray, size: Int) {
        if (compressedSize < 0 || size < 0) throw ParquetFormatException("'${leaf.name}': negative page size ($source)")
        if (chunk.codec == ParquetCodec.UNCOMPRESSED && compressedSize != size) throw ParquetFormatException("'${leaf.name}': uncompressed page sizes disagree ($compressedSize vs $size) ($source)")
        val written = try {
            decompressor.decompress(src, at, compressedSize, dst, 0, size)
        } catch (e: ParquetFormatException) {
            throw ParquetFormatException("'${leaf.name}': ${e.message} ($source)", e)
        } catch (e: RuntimeException) {
            throw ParquetFormatException("'${leaf.name}': ${chunk.codec} page failed to decompress: ${e.message} ($source)", e)
        }
        if (written != size) throw ParquetFormatException("'${leaf.name}': ${chunk.codec} page produced $written bytes, header says $size ($source)")
    }

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

    private fun readPageHeader(b: ByteArray, at: Int, available: Int, offset: Long, viewEnd: Long): PageHeader {
        val r = ThriftCompactReader(ByteCursor(b, at, at + available))
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
            if (viewEnd < dataEnd) throw WindowTooShort(offset)
            throw ParquetFormatException("'${leaf.name}': malformed page header at $offset: ${e.message} ($source)", e)
        } catch (e: RuntimeException) {
            throw ParquetFormatException("'${leaf.name}': malformed page header at $offset: ${e.message} ($source)", e)
        }
        if (compressed < 0 || uncompressed < 0 || numValues < 0) throw ParquetFormatException("'${leaf.name}': negative page size at $offset ($source)")
        return PageHeader(type, uncompressed, compressed, numValues, encoding, defEnc, r.position, numNulls, defLen, repLen, isCompressed)
    }

    private companion object {
        const val DATA_PAGE = 0
        const val INDEX_PAGE = 1
        const val DICTIONARY_PAGE = 2
        const val DATA_PAGE_V2 = 3
        const val MAX_PAGES = 100_000
    }
}

/**
 * A streaming reader of the RLE / bit-packed hybrid used for definition levels and dictionary indices,
 * for any bit width 0..32 (0 means every value is 0, which a one-entry dictionary produces). One
 * instance is reset per page, so decoding allocates nothing.
 */
internal class RleDecoder {
    private var data = ByteArray(0)
    private var pos = 0
    private var limit = 0
    private var bitWidth = 0
    private var byteWidth = 0
    private var mask = 0L
    private var runLeft = 0
    private var runValue = 0
    private var packedLeft = 0
    private var bitBuffer = 0L
    private var bits = 0
    private var column = ""
    private var source = ""

    fun reset(data: ByteArray, from: Int, limit: Int, bitWidth: Int, column: String, source: String): RleDecoder {
        if (bitWidth !in 0..32) throw ParquetFormatException("'$column': bit width $bitWidth ($source)")
        this.data = data
        this.pos = from
        this.limit = limit
        this.bitWidth = bitWidth
        this.byteWidth = (bitWidth + 7) / 8
        this.mask = (1L shl bitWidth) - 1
        this.runLeft = 0
        this.packedLeft = 0
        this.bitBuffer = 0L
        this.bits = 0
        this.column = column
        this.source = source
        return this
    }

    fun next(): Int {
        while (true) {
            if (runLeft > 0) {
                runLeft--
                return runValue
            }
            if (packedLeft > 0) {
                packedLeft--
                while (bits < bitWidth) {
                    if (pos >= limit) throw ParquetFormatException("'$column': bit-packed data truncated ($source)")
                    bitBuffer = bitBuffer or ((data[pos++].toLong() and 0xFF) shl bits)
                    bits += 8
                }
                val value = (bitBuffer and mask).toInt()
                bitBuffer = bitBuffer ushr bitWidth
                bits -= bitWidth
                return value
            }
            readHeader()
        }
    }

    /** How many of the next [n] values are 1, consuming them. */
    fun countOnes(n: Int): Int {
        var ones = 0
        for (i in 0 until n) if (next() == 1) ones++
        return ones
    }

    private fun readHeader() {
        var header = 0L
        var shift = 0
        while (true) {
            if (pos >= limit) throw ParquetFormatException("'$column': RLE/bit-packed data ends early ($source)")
            val b = data[pos++].toInt() and 0xFF
            header = header or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            if (shift > 63) throw ParquetFormatException("'$column': RLE header varint too long ($source)")
        }
        if (header and 1L == 0L) {
            val run = header ushr 1
            if (run > Int.MAX_VALUE) throw ParquetFormatException("'$column': RLE run of $run values ($source)")
            var value = 0
            for (k in 0 until byteWidth) {
                if (pos >= limit) throw ParquetFormatException("'$column': RLE run value truncated ($source)")
                value = value or ((data[pos++].toInt() and 0xFF) shl (8 * k))
            }
            runLeft = run.toInt()
            runValue = value
        } else {
            val groups = header ushr 1
            if (groups > Int.MAX_VALUE / 8) throw ParquetFormatException("'$column': bit-packed run of $groups groups ($source)")
            packedLeft = groups.toInt() * 8
            bitBuffer = 0L
            bits = 0
        }
    }
}
