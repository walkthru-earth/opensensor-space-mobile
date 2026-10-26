package space.opensensor.data.history.parquet

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * The page index against a real PyArrow file. `parquet/discover/cells-24h-res7-pageindex.parquet` is the
 * Discover sample `cells/window=24h/date=2026-10-08/hour=10/h3_res=7/data.parquet` written by
 * `tools/discover-fixture/make_discover_sample.py` with PyArrow 25.0.1: one row group of 190 rows sorted
 * by `h3_index`, SNAPPY, PLAIN, data page V1, `write_batch_size=16` (pages of 16 rows) and
 * `write_page_index=True`. The per-page h3 bounds and null counts below come from PyArrow
 * (`uv run --with pyarrow==25.0.1`) over the same rows split in sixteens; PyArrow has no offset index API,
 * so the page boundaries come from the writer config and are checked against the decoded values.
 */
class PageIndexTest {

    private val path = "parquet/discover/cells-24h-res7-pageindex.parquet"
    private val bytes by lazy { fixtureBytes(path) }
    private val file by lazy { ParquetFile.wrap(bytes, path) }
    private val md get() = file.metadata

    private fun view(range: LongRange): ByteView = ByteView(bytes, range.first.toInt(), rangeLength(range))

    private fun offsetIndex(column: String): OffsetIndex = OffsetIndex.parse(view(md.rowGroups[0].columns[file.leaf(column).index].offsetIndex!!))

    private fun columnIndex(column: String): ColumnIndex = ColumnIndex.parse(view(md.rowGroups[0].columns[file.leaf(column).index].columnIndex!!))

    private val h3: LongArray by lazy { (file.readColumn("h3_index") as ColumnValues.Int64).values }

    @Test
    fun `fixture is the pyarrow page index sample`() {
        assertEquals(31167, bytes.size)
        assertEquals("parquet-cpp-arrow version 25.0.1", md.createdBy)
        assertEquals(190L, md.numRows)
        assertEquals(1, md.rowGroups.size)
        assertEquals(23, md.leaves.size)
        assertEquals(ParquetType.INT64, file.leaf("h3_index").type)
        assertEquals(ParquetType.INT32, file.leaf("devices").type)
        assertEquals(ParquetType.DOUBLE, file.leaf("pm25__mean").type)
        assertEquals(ParquetRepetition.OPTIONAL, file.leaf("pm25__mean").repetition)
        assertEquals(190, h3.size)
        for (i in 1 until h3.size) assertTrue("h3 sorted strictly at $i", h3[i - 1] < h3[i])
        for (c in md.rowGroups[0].columns) {
            assertEquals("${c.name} codec", ParquetCodec.SNAPPY, c.codec)
            assertNull("${c.name} has no dictionary", c.dictionaryPageOffset)
        }
    }

    @Test
    fun `every column chunk has both page indexes after the column data and before the footer`() {
        val dataEnd = bytes.size - 8L - md.footerLength
        val chunks = md.rowGroups[0].columns
        val columnDataEnd = chunks.maxOf { it.firstPageOffset + it.totalCompressedSize }
        val seen = ArrayList<LongRange>()
        for ((i, c) in chunks.withIndex()) {
            val oi = assertNotNullRange("${c.name} offset index", c.offsetIndex)
            val ci = assertNotNullRange("${c.name} column index", c.columnIndex)
            for ((what, r) in listOf("offset" to oi, "column" to ci)) {
                assertTrue("${c.name} $what index $r non-empty", !r.isEmpty())
                assertTrue("${c.name} $what index $r after the column data ($columnDataEnd)", r.first >= columnDataEnd)
                assertTrue("${c.name} $what index $r before the footer ($dataEnd)", r.last < dataEnd)
                for (other in seen) assertTrue("${c.name} $what index $r overlaps $other", r.last < other.first || r.first > other.last)
                seen += r
            }
            val location = md.chunkLocation(0, md.leaves[i], bytes.size.toLong())
            assertEquals("${c.name} location offset index", oi, location.offsetIndex)
            assertEquals("${c.name} location column index", ci, location.columnIndex)
            assertEquals("${c.name} location pages", c.firstPageOffset until c.firstPageOffset + c.totalCompressedSize, location.pages)
        }
    }

    private fun assertNotNullRange(message: String, r: LongRange?): LongRange {
        assertNotNull(message, r)
        return r!!
    }

    @Test
    fun `offset index of h3_index has 12 pages of 16 rows inside the chunk`() {
        val oi = offsetIndex("h3_index")
        val chunk = md.rowGroups[0].columns[0]
        val chunkEnd = chunk.firstPageOffset + chunk.totalCompressedSize
        assertEquals(12, oi.pageCount)
        assertEquals((0 until 12).map { it * 16L }, oi.firstRows.toList())
        assertEquals("first page is the chunk's first page", chunk.dataPageOffset, oi.offsets[0])
        for (p in 0 until oi.pageCount) {
            assertTrue("page $p size ${oi.sizes[p]}", oi.sizes[p] > 0)
            assertTrue("page $p starts in the chunk", oi.offsets[p] >= chunk.firstPageOffset)
            assertTrue("page $p ends in the chunk", oi.pageRange(p).last < chunkEnd)
            if (p > 0) assertTrue("page offsets increase at $p", oi.offsets[p] > oi.offsets[p - 1])
            if (p + 1 < oi.pageCount) assertTrue("page $p ends before page ${p + 1}", oi.pageRange(p).last < oi.offsets[p + 1])
        }
        assertEquals("last page ends the chunk", chunkEnd - 1, oi.pageRange(11).last)
        assertEquals(176L, oi.firstRows[11])
        assertEquals(190L, oi.pageRowEnd(11, md.rowGroups[0].numRows))
        assertEquals(32L, oi.pageRowEnd(1, md.rowGroups[0].numRows))
    }

    @Test
    fun `pages split on 16 row batches and all-null batches join the page before`() {
        // Required columns flush a page every 16 rows. A batch of 16 nulls adds no value bytes, so the
        // writer does not flush after it and it shares a page with the next batch.
        val sixteens = (0 until 12).map { it * 16L }
        var total = 0
        for (leaf in md.leaves) {
            val oi = offsetIndex(leaf.name)
            total += oi.pageCount
            assertEquals("${leaf.name} column index pages", oi.pageCount, columnIndex(leaf.name).pageCount)
            if (leaf.repetition == ParquetRepetition.REQUIRED) {
                assertEquals("${leaf.name} pages", sixteens, oi.firstRows.toList())
            } else {
                assertEquals("${leaf.name} first page", 0L, oi.firstRows[0])
                assertTrue("${leaf.name} pages start on a batch: ${oi.firstRows.toList()}", oi.firstRows.all { it in sixteens })
            }
        }
        assertEquals(listOf(0L, 16, 32, 48, 64, 80, 112, 128, 144, 160, 176), offsetIndex("pm25__n").firstRows.toList())
        assertEquals("pages of the 23 columns", TOTAL_PAGES, total)
    }

    @Test
    fun `column index of h3_index matches pyarrow and the decoded pages`() {
        val ci = columnIndex("h3_index")
        val oi = offsetIndex("h3_index")
        assertEquals(12, ci.pageCount)
        assertEquals("ascending", 1, ci.boundaryOrder)
        for (p in 0 until 12) {
            assertTrue("page $p is not all null", !ci.nullPages[p])
            val from = oi.firstRows[p].toInt()
            val until = oi.pageRowEnd(p, 190).toInt()
            val rows = h3.copyOfRange(from, until)
            assertEquals("page $p min (decoded)", rows.min(), ci.minLong(p, ParquetType.INT64))
            assertEquals("page $p max (decoded)", rows.max(), ci.maxLong(p, ParquetType.INT64))
            assertEquals("page $p min (pyarrow)", H3_PAGE_MIN[p], ci.minLong(p, ParquetType.INT64))
            assertEquals("page $p max (pyarrow)", H3_PAGE_MAX[p], ci.maxLong(p, ParquetType.INT64))
            ci.nullCounts?.let { assertEquals("page $p null count", 0L, it[p]) }
        }
    }

    @Test
    fun `column index of a nullable column counts the decoded nulls per page`() {
        for (name in listOf("pm25__mean", "pm25__n", "co2__mean")) {
            val ci = columnIndex(name)
            val oi = offsetIndex(name)
            val values = file.readColumn(name)
            val nullCounts = ci.nullCounts
            for (p in 0 until ci.pageCount) {
                val from = oi.firstRows[p].toInt()
                val until = oi.pageRowEnd(p, 190).toInt()
                val nulls = (from until until).count { values.isNull(it) }
                if (nullCounts != null) assertEquals("$name page $p null count", nulls.toLong(), nullCounts[p])
                assertEquals("$name page $p all null", nulls == until - from, ci.nullPages[p])
                if (!ci.nullPages[p] && values is ColumnValues.Float64) {
                    val defined = (from until until).filter { !values.isNull(it) }.map { values.values[it] }
                    assertEquals("$name page $p min", defined.min(), Double.fromBits(leLong(ci.minValues[p], 0)), 0.0)
                    assertEquals("$name page $p max", defined.max(), Double.fromBits(leLong(ci.maxValues[p], 0)), 0.0)
                }
            }
        }
        // PyArrow: pm25 is null for 10 rows of batch 4 and all 16 of batch 5, which shares page 5 with batch 6.
        val pm25 = columnIndex("pm25__mean")
        assertNotNull("pyarrow writes null counts", pm25.nullCounts)
        assertEquals(PM25_PAGE_NULLS, pm25.nullCounts!!.toList())
        assertEquals(List(11) { false }, pm25.nullPages.toList())
    }

    @Test
    fun `a range covering one page reads one page per column and returns those rows`() = runTest {
        val names = md.leaves.map { it.name }
        val expected = names.associateWith { rows(file.readColumn(it)) }
        for (page in listOf(0, 3, 11)) {
            val range = H3_PAGE_MIN[page]..H3_PAGE_MAX[page]
            val want = (page * 16 until minOf(page * 16 + 16, 190)).toList()
            val pruned = scan(ScanRequest(names, filterColumn = "h3_index", filterRanges = listOf(range)))
            assertEquals("page $page rows", want.size, pruned.rowCount)
            assertEquals("page $page pages read", names.size, pruned.pagesRead)
            assertEquals("page $page pages total", TOTAL_PAGES, pruned.pagesTotal)
            assertEquals(1, pruned.rowGroupsRead)
            assertRows("page $page pruned", names, expected, want, pruned)

            val full = scan(ScanRequest(names, filterColumn = "h3_index", filterRanges = listOf(range), usePageIndex = false))
            assertEquals("page $page pages read without the index", TOTAL_PAGES, full.pagesRead)
            assertEquals("page $page rows without the index", want.size, full.rowCount)
            assertRows("page $page full", names, expected, want, full)
        }
    }

    @Test
    fun `a range spanning a page boundary reads both pages`() = runTest {
        val names = listOf("h3_index", "updated", "pm25__mean")
        val expected = names.associateWith { rows(file.readColumn(it)) }
        val batch = scan(ScanRequest(names, filterColumn = "h3_index", filterRanges = listOf(h3[45]..h3[50])))
        assertEquals(6, batch.rowCount)
        assertEquals(names.size * 2, batch.pagesRead)
        assertRows("boundary", names, expected, (45..50).toList(), batch)
    }

    @Test
    fun `ranges between pages or outside every value return no rows`() = runTest {
        val names = listOf("h3_index", "devices", "pm25__mean")
        var gaps = 0
        for (p in 0 until 11) {
            if (H3_PAGE_MAX[p] + 1 > H3_PAGE_MIN[p + 1] - 1) continue
            gaps++
            val gap = (H3_PAGE_MAX[p] + 1)..(H3_PAGE_MIN[p + 1] - 1)
            val batch = scan(ScanRequest(names, filterColumn = "h3_index", filterRanges = listOf(gap)))
            assertEquals("gap after page $p rows", 0, batch.rowCount)
            assertEquals("gap after page $p pages", 0, batch.pagesRead)
            for (n in names) assertEquals("gap after page $p column $n", 0, batch[n]!!.size)
            val full = scan(ScanRequest(names, filterColumn = "h3_index", filterRanges = listOf(gap), usePageIndex = false))
            assertEquals("gap after page $p rows without the index", 0, full.rowCount)
        }
        assertEquals("every page boundary of the sorted h3 cells has a gap", 11, gaps)
        for (outside in listOf(Long.MIN_VALUE..(H3_PAGE_MIN[0] - 1), (H3_PAGE_MAX[11] + 1)..Long.MAX_VALUE, 0L..1000L)) {
            val batch = scan(ScanRequest(names, filterColumn = "h3_index", filterRanges = listOf(outside)))
            assertEquals("$outside rows", 0, batch.rowCount)
            assertEquals("$outside row groups", 0, batch.rowGroupsRead)
            assertEquals("$outside pages", 0, batch.pagesRead)
        }
    }

    @Test
    fun `a column without an offset index reads the row group whole and still filters exactly`() = runTest {
        val names = listOf("h3_index", "updated", "pm25__mean")
        val expected = names.associateWith { rows(file.readColumn(it)) }
        val pm25 = file.leaf("pm25__mean").index
        val stripped = md.copy(
            rowGroups = md.rowGroups.map { rg ->
                rg.copy(columns = rg.columns.mapIndexed { i, c -> if (i == pm25) c.copy(offsetIndex = null, columnIndex = null) else c })
            },
        )
        val cache = FooterCache()
        val source = MemoryRangeSource(bytes, path)
        cache.put(source.id(), stripped)
        val batch = ParquetReader.open(source, footerCache = cache).use {
            it.scan(ScanRequest(names, filterColumn = "h3_index", filterRanges = listOf(h3[45]..h3[50])))
        }
        assertEquals(6, batch.rowCount)
        assertEquals("every page of the three columns", batch.pagesTotal, batch.pagesRead)
        assertRows("partial index", names, expected, (45..50).toList(), batch)
    }

    @Test
    fun `an offset index must ascend inside its chunk and start at row 0`() {
        val oi = offsetIndex("h3_index")
        val chunk = md.chunkLocation(0, file.leaf("h3_index"), bytes.size.toLong()).pages
        oi.checkWithin(chunk, 190)
        fun bad(label: String, offsets: LongArray = oi.offsets, sizes: IntArray = oi.sizes, firstRows: LongArray = oi.firstRows) {
            assertFailsWith<ParquetFormatException>(label) { OffsetIndex(offsets, sizes, firstRows).checkWithin(chunk, 190) }
        }
        bad("first row not 0", firstRows = oi.firstRows.copyOf().also { it[0] = 1 })
        bad("pages overlap", offsets = oi.offsets.copyOf().also { it[1] = it[0] })
        bad("page before the chunk", offsets = oi.offsets.copyOf().also { it[0] = chunk.first - 1 })
        bad("page past the chunk", sizes = oi.sizes.copyOf().also { it[11] += 1 })
        bad("offset near Long.MAX_VALUE", offsets = oi.offsets.copyOf().also { it[11] = Long.MAX_VALUE - 1 })
        bad("page starts past the rows", firstRows = oi.firstRows.copyOf().also { it[11] = 190 })
    }

    private suspend fun scan(request: ScanRequest): ColumnBatch =
        ParquetReader.open(MemoryRangeSource(bytes, path)).use { it.scan(request) }

    private fun rows(c: ColumnValues): List<Any?> = List(c.size) { i ->
        if (c.isNull(i)) {
            null
        } else {
            when (c) {
                is ColumnValues.Int32 -> c.values[i]
                is ColumnValues.Int64 -> c.values[i]
                is ColumnValues.Float32 -> c.values[i]
                is ColumnValues.Float64 -> c.values[i]
                is ColumnValues.Binary -> c.string(i)
            }
        }
    }

    private fun assertRows(label: String, names: List<String>, expected: Map<String, List<Any?>>, want: List<Int>, batch: ColumnBatch) {
        assertEquals("$label columns", names, batch.columns.keys.toList())
        for (n in names) {
            val got = rows(batch[n]!!.toColumnValues())
            assertEquals("$label $n", want.map { expected.getValue(n)[it] }, got)
        }
    }

    private companion object {
        val H3_PAGE_MIN = longArrayOf(
            608533004528123903, 608533320342437887, 608533321617506303, 608725925114150911,
            608725951840255999, 608992235534417919, 609083434148560895, 609083436027609087,
            609083438074429439, 610139773452943359, 610449981878304767, 610449987683221503,
        )
        val H3_PAGE_MAX = longArrayOf(
            608533320157888511, 608533321600729087, 608725924761829375, 608725951823478783,
            608992235500863487, 608992240651468799, 609083435557847039, 609083438040875007,
            610139773436166143, 610139778955870207, 610449987666444287, 610450141781950463,
        )
        val PM25_PAGE_NULLS = listOf(0L, 0, 0, 0, 10, 16, 0, 0, 0, 0, 0)

        /** Pages of all 23 column chunks, from the offset indexes. */
        const val TOTAL_PAGES = 224
    }
}
