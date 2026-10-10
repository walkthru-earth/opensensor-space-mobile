package earth.walkthru.aqsync.data.history.parquet

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Footers and page indexes of recently opened files, keyed by [SourceId], so reopening a file the app
 * read a moment ago parses nothing. Least recently used entries go first once [maxEntries] is reached.
 * A [SourceId] names one immutable version of a file, so an entry never goes stale; a source that
 * changed under a reader is dropped with [invalidate].
 */
class FooterCache(val maxEntries: Int = 256) {
    init {
        require(maxEntries > 0)
    }

    internal class Entry(val metadata: ParquetFileMetadata) {
        val offsetIndexes = HashMap<Long, OffsetIndex>()
        val columnIndexes = HashMap<Long, ColumnIndex>()
    }

    private val mutex = Mutex()
    private val entries = LinkedHashMap<SourceId, Entry>()

    val size: Int get() = entries.size

    internal suspend fun get(id: SourceId): Entry? = mutex.withLock {
        // Reinsert on access so iteration order is least recently used first.
        entries.remove(id)?.also { entries[id] = it }
    }

    internal suspend fun put(id: SourceId, metadata: ParquetFileMetadata): Entry = mutex.withLock {
        entries.remove(id)?.let { existing ->
            entries[id] = existing
            return@withLock existing
        }
        while (entries.size >= maxEntries) entries.remove(entries.keys.first())
        Entry(metadata).also { entries[id] = it }
    }

    internal suspend fun <T> withEntry(entry: Entry, block: (Entry) -> T): T = mutex.withLock { block(entry) }

    suspend fun invalidate(id: SourceId) {
        mutex.withLock { entries.remove(id) }
    }

    suspend fun clear() {
        mutex.withLock { entries.clear() }
    }
}

/**
 * What a [ParquetReader.scan] decodes: [columns] (names the file lacks are left out of the batch), in
 * [rowGroups] (default all), keeping only rows whose INT32 or INT64 [filterColumn] falls inside one of
 * [filterRanges] (inclusive; a null value never matches). Row groups are pruned by their footer
 * statistics and, when [usePageIndex] is set and the file has a page index, pages by their column index,
 * so only the pages that may hold a matching row are fetched and decoded. The exact filter runs last.
 */
class ScanRequest(
    val columns: List<String>,
    val rowGroups: List<Int>? = null,
    val filterColumn: String? = null,
    val filterRanges: List<LongRange> = emptyList(),
    val usePageIndex: Boolean = true,
) {
    init {
        require(filterColumn != null || filterRanges.isEmpty()) { "filterRanges need a filterColumn" }
    }
}

/**
 * Reads Parquet over a [RangeSource] (a local file, bytes in memory, HTTP ranges). [open] makes one tail
 * read of [RangePolicy.tailPrefetchBytes], which holds the footer and the page index of the files the app
 * reads; a file under [RangePolicy.wholeFileBelowBytes] is then read whole the first time a scan needs
 * bytes outside the tail. Every other read of a [scan] is planned and coalesced into as few requests as
 * the [RangePolicy] allows, and the bytes are decoded where they landed, without copies.
 *
 * Decoding writes every value straight into its final slot of a [PrimitiveColumn]; dictionary pages are
 * decoded once per chunk and looked up per row. The supported Parquet subset is [ParquetFile]'s.
 *
 * When the source reports a [SourceChangedException] (the object was replaced between two reads), the
 * reader drops the cached footer, opens the new version and runs the scan again, once.
 */
class ParquetReader private constructor(
    private val raw: RangeSource,
    private val codecs: ParquetCodecs,
    private val cache: FooterCache,
    val policy: RangePolicy,
) : AutoCloseable {
    private val source = CoalescingRangeSource(raw, policy)
    private lateinit var state: State

    private class State(
        val id: SourceId,
        val entry: FooterCache.Entry,
        /** Bytes already held, by file offset: the tail, and the whole file once it was read. */
        val held: MutableList<Pair<Long, ByteView>>,
    ) {
        val metadata: ParquetFileMetadata get() = entry.metadata
    }

    val id: SourceId get() = state.id
    val metadata: ParquetFileMetadata get() = state.metadata
    val leaves: List<LeafColumn> get() = metadata.leaves
    val numRows: Long get() = metadata.numRows
    val rowGroupCount: Int get() = metadata.rowGroups.size

    /** Decodes [request] into one [ColumnBatch]. */
    suspend fun scan(request: ScanRequest): ColumnBatch = try {
        scanOnce(request)
    } catch (e: SourceChangedException) {
        cache.invalidate(e.previous)
        load()
        scanOnce(request)
    }

    /** All rows of [columns], every row group. */
    suspend fun readColumns(columns: List<String>): ColumnBatch = scan(ScanRequest(columns))

    override fun close() = raw.close()

    private suspend fun load() {
        val tail = raw.readTail(policy.tailPrefetchBytes)
        val id = raw.id()
        if (id.size < 12) throw ParquetFormatException("file too small (${id.size} bytes) (${id.uri})")
        val tailStart = id.size - tail.length
        val held = mutableListOf(tailStart to tail)
        val entry = cache.get(id) ?: run {
            val metadata = try {
                ParquetFooterParser.parseTail(tail, id.size) { start, length -> raw.readRanges(listOf(byteRange(start, length))).single() }
            } catch (e: ParquetFormatException) {
                throw ParquetFormatException("${e.message} (${id.uri})", e)
            }
            cache.put(id, metadata)
        }
        state = State(id, entry, held)
    }

    /** One view per range, served from bytes already held when possible, the rest in one planned read. */
    private suspend fun read(ranges: List<LongRange>): List<ByteView> {
        val s = state
        val out = arrayOfNulls<ByteView>(ranges.size)
        fun fromHeld(): List<Int> {
            val missing = ArrayList<Int>()
            for (i in ranges.indices) {
                if (out[i] != null) continue
                val r = ranges[i]
                if (r.isEmpty()) {
                    out[i] = ByteView.EMPTY
                    continue
                }
                val length = rangeLength(r)
                val hit = s.held.firstOrNull { (start, view) -> r.first >= start && r.last < start + view.length }
                if (hit == null) missing += i else out[i] = hit.second.slice((r.first - hit.first).toInt(), length)
            }
            return missing
        }
        var missing = fromHeld()
        if (missing.isNotEmpty() && s.id.size <= policy.wholeFileBelowBytes && s.held.none { it.first == 0L }) {
            // A small file costs one request whatever we read, so read all of it before the tail once.
            val tailStart = s.held.minOf { it.first }
            if (tailStart > 0) s.held += 0L to raw.readRanges(listOf(0L until tailStart)).single()
            missing = fromHeld()
        }
        if (missing.isNotEmpty()) {
            for (r in missing.map { ranges[it] }) {
                if (r.first < 0 || r.last >= s.id.size) throw ParquetFormatException("bytes $r outside the file (${s.id.uri})")
            }
            val views = source.readRanges(missing.map { ranges[it] })
            missing.forEachIndexed { k, i -> out[i] = views[k] }
        }
        return out.map { it!! }
    }

    private class Work(val leaf: LeafColumn, val sink: ColumnSink)

    private class Plan(val rowGroup: Int, val selection: RowSelection?)

    private suspend fun scanOnce(request: ScanRequest): ColumnBatch {
        val s = state
        val metadata = s.metadata
        val chunks = ColumnChunks(metadata, s.id.uri, codecs, s.id.size)
        val wanted = request.columns.distinct().mapNotNull { metadata.leaf(it) }
        val filterLeaf = request.filterColumn?.let { name ->
            val leaf = metadata.leaf(name) ?: throw ParquetFormatException("no column '$name' in ${s.id.uri}")
            if (leaf.type != ParquetType.INT64 && leaf.type != ParquetType.INT32) throw ParquetFormatException("'$name' is ${leaf.type}, not INT32/INT64 (${s.id.uri})")
            leaf
        }
        val decoded = if (filterLeaf != null && wanted.none { it.index == filterLeaf.index }) wanted + filterLeaf else wanted

        var groups = request.rowGroups?.distinct()?.sorted() ?: metadata.rowGroups.indices.toList()
        for (g in groups) if (g !in metadata.rowGroups.indices) throw ParquetFormatException("row group $g does not exist in ${s.id.uri}")
        if (filterLeaf != null) {
            val ranges = request.filterRanges.filter { !it.isEmpty() }
            val kept = metadata.rowGroupsOverlapping(filterLeaf.name, ranges).toSet()
            groups = groups.filter { it in kept }
        }

        // Page index: which rows of each row group may match, and where each column's pages are.
        val usePages = filterLeaf != null && request.usePageIndex
        val offsetIndexes = HashMap<Long, OffsetIndex>()
        val plans = ArrayList<Plan>()
        if (usePages) loadPageIndexes(groups, decoded, filterLeaf!!, offsetIndexes)
        for (g in groups) {
            val selection = if (usePages) selectRows(g, filterLeaf!!, request.filterRanges, offsetIndexes) else null
            if (selection != null && selection.starts.isEmpty()) continue
            plans += Plan(g, selection)
        }

        // Every byte range of the scan, fetched in one planned read.
        class Segment(val plan: Plan, val leaf: LeafColumn, val whole: ChunkRange?, val prefix: LongRange?, val pages: IntArray)
        val segments = ArrayList<Segment>()
        val ranges = ArrayList<LongRange>()
        for (plan in plans) {
            for (leaf in decoded) {
                val oi = offsetIndexes[key(plan.rowGroup, leaf.index)]
                val sel = plan.selection
                if (sel == null || oi == null || oi.pageCount == 0) {
                    val range = chunks.range(leaf, plan.rowGroup)
                    segments += Segment(plan, leaf, range, null, IntArray(0))
                    ranges += byteRange(range.start, range.length)
                } else {
                    val rows = metadata.rowGroups[plan.rowGroup].numRows
                    val pages = (0 until oi.pageCount).filter { sel.overlaps(oi.firstRows[it], oi.pageRowEnd(it, rows)) }.toIntArray()
                    val chunk = metadata.rowGroups[plan.rowGroup].columns[leaf.index]
                    val prefix = chunk.firstPageOffset until oi.offsets[0]
                    segments += Segment(plan, leaf, null, prefix, pages)
                    ranges += prefix
                    for (p in pages) ranges += oi.pageRange(p)
                }
            }
        }
        val views = read(ranges)

        // Decode each column into one sink sized for the selected rows.
        val rowCount = plans.sumOf { it.selection?.count() ?: metadata.rowGroups[it.rowGroup].numRows }
        val work = decoded.map { Work(it, chunks.sink(it, rowCount, plans.map { p -> p.rowGroup })) }
        val scratch = DecodeScratch()
        var pagesRead = 0
        var pagesTotal = 0
        var v = 0
        for (segment in segments) {
            val sink = work.first { it.leaf.index == segment.leaf.index }.sink
            val g = segment.plan.rowGroup
            if (segment.whole != null) {
                val range = segment.whole
                val pages = chunks.decode(sink, scratch, segment.leaf, g, range, views[v++]) {
                    chunks.widen(range).let { wide -> wide to read(listOf(byteRange(wide.start, wide.length))).single() }
                }
                pagesRead += pages
                pagesTotal += pages
            } else {
                val oi = offsetIndexes.getValue(key(g, segment.leaf.index))
                val (rg, chunk) = chunks.chunk(segment.leaf, g)
                val decoder = ChunkDecoder(segment.leaf, chunk, rg.numRows, segment.plan.selection, sink, scratch, codecs, chunks.dataEnd, s.id.uri)
                val prefix = views[v++]
                val pageViews = List(segment.pages.size) { views[v++] }
                val starts = LongArray(segment.pages.size) { oi.offsets[segment.pages[it]] }
                val firstRows = LongArray(segment.pages.size) { oi.firstRows[segment.pages[it]] }
                try {
                    decoder.decodePages(prefix, segment.prefix!!.first, pageViews, starts, firstRows)
                } catch (e: ChunkDecoder.WindowTooShort) {
                    throw ParquetFormatException("'${segment.leaf.name}': page at ${e.offset} does not match its offset index (${s.id.uri})")
                }
                pagesRead += decoder.pages
                pagesTotal += oi.pageCount
            }
        }
        val columns = work.associate { it.leaf to it.sink.build() }
        val batch = if (filterLeaf != null) keepMatching(columns, filterLeaf, request.filterRanges) else columns
        val rows = batch.values.firstOrNull()?.size ?: if (filterLeaf != null) 0 else rowCount.toInt()
        val out = LinkedHashMap<String, PrimitiveColumn>()
        for (leaf in wanted) out[leaf.name] = batch.getValue(leaf)
        return ColumnBatch(rows, out, plans.size, metadata.rowGroups.size, pagesRead, pagesTotal)
    }

    /** Reads the page indexes the scan needs and has not cached: the filter column's both, every other column's offset index. */
    private suspend fun loadPageIndexes(groups: List<Int>, decoded: List<LeafColumn>, filterLeaf: LeafColumn, offsetIndexes: HashMap<Long, OffsetIndex>) {
        val s = state
        val entry = s.entry
        class Want(val key: Long, val column: Boolean, val range: LongRange)
        val wants = ArrayList<Want>()
        cache.withEntry(entry) { e ->
            for (g in groups) {
                for (leaf in decoded) {
                    val k = key(g, leaf.index)
                    val location = s.metadata.chunkLocation(g, leaf, s.id.size)
                    val oi = e.offsetIndexes[k]
                    if (oi != null) offsetIndexes[k] = oi else location.offsetIndex?.let { wants += Want(k, false, it) }
                    if (leaf.index == filterLeaf.index && e.columnIndexes[k] == null) location.columnIndex?.let { wants += Want(k, true, it) }
                }
            }
        }
        if (wants.isEmpty()) return
        val views = read(wants.map { it.range })
        val parsedOffsets = HashMap<Long, OffsetIndex>()
        val parsedColumns = HashMap<Long, ColumnIndex>()
        wants.forEachIndexed { i, w ->
            try {
                if (w.column) parsedColumns[w.key] = ColumnIndex.parse(views[i]) else parsedOffsets[w.key] = OffsetIndex.parse(views[i])
            } catch (e: ParquetFormatException) {
                throw ParquetFormatException("${e.message} (${s.id.uri})", e)
            }
        }
        offsetIndexes.putAll(parsedOffsets)
        cache.withEntry(entry) { e ->
            e.offsetIndexes.putAll(parsedOffsets)
            e.columnIndexes.putAll(parsedColumns)
        }
    }

    /**
     * The rows of [rowGroup] whose page of [filterLeaf] may hold a value in [ranges], by the column index;
     * null (every row) when the file has no usable page index for it.
     */
    private suspend fun selectRows(rowGroup: Int, filterLeaf: LeafColumn, ranges: List<LongRange>, offsetIndexes: Map<Long, OffsetIndex>): RowSelection? {
        val k = key(rowGroup, filterLeaf.index)
        val oi = offsetIndexes[k] ?: return null
        val ci = cache.withEntry(state.entry) { it.columnIndexes[k] } ?: return null
        if (ci.pageCount != oi.pageCount) throw ParquetFormatException("'${filterLeaf.name}': column index has ${ci.pageCount} pages, offset index ${oi.pageCount} (${state.id.uri})")
        val rows = state.metadata.rowGroups[rowGroup].numRows
        val from = ArrayList<Long>()
        val until = ArrayList<Long>()
        for (p in 0 until oi.pageCount) {
            if (ci.nullPages[p]) continue
            val lo = ci.minLong(p, filterLeaf.type)
            val hi = ci.maxLong(p, filterLeaf.type)
            val may = lo == null || hi == null || ranges.any { r -> !r.isEmpty() && r.first <= hi && r.last >= lo }
            if (may) {
                from += oi.firstRows[p]
                until += oi.pageRowEnd(p, rows)
            }
        }
        return RowSelection.of(from.toLongArray(), until.toLongArray())
    }

    /** The rows whose [filterLeaf] value lies in one of [ranges], every column compacted alike. */
    private fun keepMatching(columns: Map<LeafColumn, PrimitiveColumn>, filterLeaf: LeafColumn, ranges: List<LongRange>): Map<LeafColumn, PrimitiveColumn> {
        val filter = columns.getValue(filterLeaf)
        val n = filter.size
        val keep = BooleanArray(n)
        var kept = 0
        val sorted = ranges.filter { !it.isEmpty() }
        for (i in 0 until n) {
            if (filter.isNull(i)) continue
            val value = when (filter) {
                is PrimitiveColumn.I64 -> filter.values[i]
                is PrimitiveColumn.I32 -> filter.values[i].toLong()
                else -> continue
            }
            if (sorted.any { value >= it.first && value <= it.last }) {
                keep[i] = true
                kept++
            }
        }
        if (kept == n) return columns
        return columns.mapValues { (_, c) -> compact(c, keep, kept) }
    }

    private fun compact(c: PrimitiveColumn, keep: BooleanArray, kept: Int): PrimitiveColumn {
        val validity = c.validity?.let { LongArray(PrimitiveColumn.words(kept)) }
        var j = 0
        fun copyValidity(i: Int) {
            if (validity != null && !c.isNull(i)) validity[j ushr 6] = validity[j ushr 6] or (1L shl (j and 63))
        }
        val bits = validity
        return when (c) {
            is PrimitiveColumn.I32 -> {
                val out = IntArray(kept)
                for (i in keep.indices) if (keep[i]) { out[j] = c.values[i]; copyValidity(i); j++ }
                PrimitiveColumn.I32(out, kept, bits.orNullIfFull(kept))
            }
            is PrimitiveColumn.I64 -> {
                val out = LongArray(kept)
                for (i in keep.indices) if (keep[i]) { out[j] = c.values[i]; copyValidity(i); j++ }
                PrimitiveColumn.I64(out, kept, bits.orNullIfFull(kept))
            }
            is PrimitiveColumn.F32 -> {
                val out = FloatArray(kept)
                for (i in keep.indices) if (keep[i]) { out[j] = c.values[i]; copyValidity(i); j++ }
                PrimitiveColumn.F32(out, kept, bits.orNullIfFull(kept))
            }
            is PrimitiveColumn.F64 -> {
                val out = DoubleArray(kept)
                for (i in keep.indices) if (keep[i]) { out[j] = c.values[i]; copyValidity(i); j++ }
                PrimitiveColumn.F64(out, kept, bits.orNullIfFull(kept))
            }
            is PrimitiveColumn.Bin -> {
                var bytes = 0
                for (i in keep.indices) if (keep[i]) bytes += c.offsets[i + 1] - c.offsets[i]
                val offsets = IntArray(kept + 1)
                val data = ByteArray(bytes)
                var at = 0
                for (i in keep.indices) {
                    if (!keep[i]) continue
                    val len = c.offsets[i + 1] - c.offsets[i]
                    offsets[j] = at
                    c.data.copyInto(data, at, c.offsets[i], c.offsets[i + 1])
                    at += len
                    copyValidity(i)
                    j++
                }
                offsets[kept] = at
                PrimitiveColumn.Bin(offsets, data, kept, bits.orNullIfFull(kept))
            }
        }
    }

    private fun LongArray?.orNullIfFull(rows: Int): LongArray? = this?.takeIf { PrimitiveColumn.popCount(it, rows) < rows }

    companion object {
        /**
         * Opens [source]: one tail read, then the footer from [footerCache] or parsed from the tail. Reads
         * go through a [CoalescingRangeSource] with [policy]. The reader owns [source] and closes it.
         */
        suspend fun open(
            source: RangeSource,
            codecs: ParquetCodecs = ParquetCodecs.builtIn,
            footerCache: FooterCache = FooterCache(maxEntries = 1),
            policy: RangePolicy = RangePolicy.LOCAL,
        ): ParquetReader {
            val reader = ParquetReader(source, codecs, footerCache, policy)
            try {
                try {
                    reader.load()
                } catch (e: SourceChangedException) {
                    footerCache.invalidate(e.previous)
                    reader.load()
                }
            } catch (e: Throwable) {
                source.close()
                throw e
            }
            return reader
        }

        private fun key(rowGroup: Int, column: Int): Long = (rowGroup.toLong() shl 32) or column.toLong()
    }
}
