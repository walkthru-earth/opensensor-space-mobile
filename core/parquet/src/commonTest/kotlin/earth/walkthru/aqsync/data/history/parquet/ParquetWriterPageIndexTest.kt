package earth.walkthru.aqsync.data.history.parquet

import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * The writer's opt in page index against the app's own readers. Each chunk is one page, so every
 * ColumnIndex and OffsetIndex has one entry. The files are also saved for the PyArrow and DuckDB
 * validator in `tools/`.
 */
class ParquetWriterPageIndexTest {

    private fun columns() = listOf(
        ParquetWriter.Column("t", ParquetType.INT64, timestampNanosUtc = true),
        ParquetWriter.Column("seq", ParquetType.INT32),
        ParquetWriter.Column("temp", ParquetType.FLOAT),
        ParquetWriter.Column("label", ParquetType.BYTE_ARRAY, utf8 = true),
    )

    private val groupSizes = listOf(40, 25, 30, 7)

    /** Row `r` of the file has `t = 1000 + 10 r`, so `t` ascends across the row groups. */
    private fun groups(): List<List<ColumnValues>> {
        var row = 0
        return groupSizes.map { n ->
            val first = row
            row += n
            val valid = BooleanArray(n) { (first + it) % 4 != 0 }
            listOf(
                ColumnValues.Int64(LongArray(n) { 1000L + (first + it) * 10L }, null),
                ColumnValues.Int32(IntArray(n) { first + it }, valid),
                ColumnValues.Float32(FloatArray(n) { if ((first + it) % 9 == 5) Float.NaN else 20f - (first + it) / 4f }, null),
                strings(List(n) { if ((first + it) % 5 == 3) null else "r${first + it}" }),
            )
        }
    }

    private fun strings(values: List<String?>): ColumnValues.Binary {
        val data = ByteOutput()
        val offsets = IntArray(values.size + 1)
        for ((i, v) in values.withIndex()) {
            if (v != null) data.write(v.encodeToByteArray())
            offsets[i + 1] = data.size
        }
        return ColumnValues.Binary(offsets, data.toByteArray(), BooleanArray(values.size) { values[it] != null })
    }

    private fun write(codec: ParquetCodec, pageIndex: Boolean?): ByteArray {
        val out = MemorySink()
        val w = if (pageIndex == null) {
            ParquetWriter(out, columns(), codec, mapOf("purpose" to "page-index-test"), "AQ Sync page-index-test", sortedBy = 0)
        } else {
            ParquetWriter(out, columns(), codec, mapOf("purpose" to "page-index-test"), "AQ Sync page-index-test", sortedBy = 0, pageIndex = pageIndex)
        }
        w.begin()
        groups().forEach { w.rowGroup(it) }
        w.finish()
        return out.toByteArray()
    }

    private fun view(bytes: ByteArray, range: LongRange) = ByteView(bytes, range.first.toInt(), rangeLength(range))

    @Test
    fun `page index round trips through ParquetFile and ParquetReader over several row groups`() = runTest {
        for (codec in listOf(ParquetCodec.UNCOMPRESSED, ParquetCodec.LZ4_RAW)) {
            val bytes = write(codec, pageIndex = true)
            saveSample("parquet-samples", "writer-pageindex-${codec.name.lowercase()}.parquet", bytes)
            val pf = ParquetFile.wrap(bytes, codec.name)
            val md = pf.metadata
            assertEquals(groupSizes.map { it.toLong() }, md.rowGroups.map { it.numRows })

            val dataEnd = md.rowGroups.flatMap { it.columns }.maxOf { it.firstPageOffset + it.totalCompressedSize }
            val footerStart = bytes.size - 8L - md.footerLength
            val columnRanges = ArrayList<LongRange>()
            val offsetRanges = ArrayList<LongRange>()
            for ((g, rg) in md.rowGroups.withIndex()) {
                for ((c, chunk) in rg.columns.withIndex()) {
                    val label = "$codec rg$g ${chunk.name}"
                    val oiRange = chunk.offsetIndex ?: fail("$label has no offset index")
                    val ciRange = chunk.columnIndex ?: fail("$label has no column index")
                    columnRanges += ciRange
                    offsetRanges += oiRange

                    val oi = OffsetIndex.parse(view(bytes, oiRange))
                    assertEquals("$label pages", 1, oi.pageCount)
                    assertEquals("$label page offset", chunk.firstPageOffset, oi.offsets[0])
                    assertEquals("$label page size", chunk.totalCompressedSize, oi.sizes[0].toLong())
                    assertEquals("$label first row", 0L, oi.firstRows[0])
                    oi.checkWithin(md.chunkLocation(g, md.leaves[c], bytes.size.toLong()).pages, rg.numRows)

                    val ci = ColumnIndex.parse(view(bytes, ciRange))
                    val stats = chunk.statistics!!
                    assertEquals("$label null page", false, ci.nullPages[0])
                    assertArrayEquals("$label min", stats.minValue!!, ci.minValues[0])
                    assertArrayEquals("$label max", stats.maxValue!!, ci.maxValues[0])
                    assertEquals("$label null count", listOf(stats.nullCount), ci.nullCounts?.toList())
                    assertEquals("$label boundary order", if (c == 0) 1 else 0, ci.boundaryOrder)
                }
            }
            // Spec order: column data, every ColumnIndex, every OffsetIndex, footer. No overlaps.
            val all = columnRanges + offsetRanges
            assertTrue("$codec index after the column data", all.all { it.first >= dataEnd })
            assertTrue("$codec index before the footer", all.all { it.last < footerStart })
            assertTrue("$codec column indexes first", columnRanges.maxOf { it.last } < offsetRanges.minOf { it.first })
            val sorted = all.sortedBy { it.first }
            for (i in 1 until sorted.size) assertEquals("$codec index ranges are contiguous", sorted[i - 1].last + 1, sorted[i].first)
            assertEquals("$codec index fills the gap", footerStart, sorted.last().last + 1)

            // Values: the blocking reader per row group, and a full scan.
            val expected = groups()
            for (g in expected.indices) {
                for ((i, leaf) in pf.leaves.withIndex()) {
                    assertEquals("$codec rg$g ${leaf.name}", rows(expected[g][i]), rows(pf.readColumn(leaf, g)))
                }
            }
            val names = columns().map { it.name }
            val want = names.indices.associate { i -> names[i] to expected.flatMap { rows(it[i]) } }
            val batch = ParquetReader.open(MemoryRangeSource(bytes, codec.name)).use { it.scan(ScanRequest(names)) }
            assertEquals(groupSizes.sum(), batch.rowCount)
            for (n in names) assertEquals("$codec scan $n", want.getValue(n), rows(batch[n]!!.toColumnValues()))
        }
    }

    @Test
    fun `a filtered scan reads the written page index and returns exactly the matching rows`() = runTest {
        val bytes = write(ParquetCodec.LZ4_RAW, pageIndex = true)
        val names = columns().map { it.name }
        val expected = groups()
        val all = names.indices.associate { i -> names[i] to expected.flatMap { rows(it[i]) } }
        // Rows 30 to 70: the end of group 0, all of group 1, the start of group 2.
        val range = 1300L..1700L
        val want = (30..70).toList()

        val cache = FooterCache()
        val source = MemoryRangeSource(bytes, "pageindex")
        val batch = ParquetReader.open(source, footerCache = cache).use {
            it.scan(ScanRequest(names, filterColumn = "t", filterRanges = listOf(range)))
        }
        assertEquals(want.size, batch.rowCount)
        assertEquals(3, batch.rowGroupsRead)
        for (n in names) assertEquals("indexed $n", want.map { all.getValue(n)[it] }, rows(batch[n]!!.toColumnValues()))
        // Groups 0 and 2 match in part, so the reader loaded their page index: the filter column's
        // ColumnIndex and every decoded column's OffsetIndex. Group 1 lies inside the range and needs none.
        val entry = cache.get(source.id())!!
        assertEquals("column indexes loaded", 2, entry.columnIndexes.size)
        assertEquals("offset indexes loaded", 2 * names.size, entry.offsetIndexes.size)

        val plainCache = FooterCache()
        val plain = ParquetReader.open(MemoryRangeSource(bytes, "pageindex"), footerCache = plainCache).use {
            it.scan(ScanRequest(names, filterColumn = "t", filterRanges = listOf(range), usePageIndex = false))
        }
        assertEquals(want.size, plain.rowCount)
        for (n in names) assertEquals("unindexed $n", want.map { all.getValue(n)[it] }, rows(plain[n]!!.toColumnValues()))
        assertEquals("no page index without usePageIndex", 0, plainCache.get(MemoryRangeSource(bytes, "pageindex").id())!!.columnIndexes.size)
    }

    @Test
    fun `without footer statistics the written column index alone prunes row groups`() = runTest {
        val bytes = write(ParquetCodec.UNCOMPRESSED, pageIndex = true)
        val md = ParquetFile.wrap(bytes).metadata
        val stripped = md.copy(
            rowGroups = md.rowGroups.map { rg -> rg.copy(columns = rg.columns.mapIndexed { i, c -> if (i == 0) c.copy(statistics = null) else c }) },
        )
        val names = listOf("t", "seq")
        val expected = groups()
        val all = names.indices.associate { i -> names[i] to expected.flatMap { rows(it[i]) } }

        suspend fun scan(usePageIndex: Boolean): ColumnBatch {
            val cache = FooterCache()
            val source = MemoryRangeSource(bytes, "stripped-$usePageIndex")
            cache.put(source.id(), stripped)
            return ParquetReader.open(source, footerCache = cache).use {
                it.scan(ScanRequest(names, filterColumn = "t", filterRanges = listOf(1300L..1350L, 1960L..1980L), usePageIndex = usePageIndex))
            }
        }
        val want = (30..35).toList() + (96..98).toList()
        val indexed = scan(usePageIndex = true)
        assertEquals("groups 0 and 3 only", 2, indexed.rowGroupsRead)
        assertEquals("one page per column per group", 2 * names.size, indexed.pagesRead)
        for (n in names) assertEquals("indexed $n", want.map { all.getValue(n)[it] }, rows(indexed[n]!!.toColumnValues()))
        val full = scan(usePageIndex = false)
        assertEquals("every group without the index", groupSizes.size, full.rowGroupsRead)
        for (n in names) assertEquals("full $n", want.map { all.getValue(n)[it] }, rows(full[n]!!.toColumnValues()))
    }

    @Test
    fun `without the flag the bytes are unchanged and the data pages match the indexed file`() {
        for (codec in listOf(ParquetCodec.UNCOMPRESSED, ParquetCodec.LZ4_RAW)) {
            val plain = write(codec, pageIndex = null)
            assertArrayEquals("$codec explicit false", plain, write(codec, pageIndex = false))
            val md = ParquetFile.wrap(plain).metadata
            assertTrue("$codec no page index", md.rowGroups.flatMap { it.columns }.all { it.offsetIndex == null && it.columnIndex == null })
            val dataEnd = md.rowGroups.flatMap { it.columns }.maxOf { it.firstPageOffset + it.totalCompressedSize }.toInt()
            val indexed = write(codec, pageIndex = true)
            assertArrayEquals("$codec same data pages", plain.copyOfRange(0, dataEnd), indexed.copyOfRange(0, dataEnd))
        }
    }

    @Test
    fun `fully null and all NaN and geometry chunks keep a readable page index`() = runTest {
        val cols = listOf(
            ParquetWriter.Column("id", ParquetType.INT64),
            ParquetWriter.Column("none", ParquetType.INT64),
            ParquetWriter.Column("nan", ParquetType.FLOAT),
            ParquetWriter.Column("geom", ParquetType.BYTE_ARRAY, geometry = ParquetWriter.GeometryAnnotation()),
        )
        fun point(x: Double, y: Double) = TestBytes().put(1).putInt(1).putDouble(x).putDouble(y).toByteArray()
        fun group(first: Int, n: Int): List<ColumnValues> {
            val wkb = (0 until n).map { point(30.0 + first + it, 31.0) }
            val offsets = IntArray(n + 1) { i -> wkb.take(i).sumOf { it.size } }
            return listOf(
                ColumnValues.Int64(LongArray(n) { (first + it).toLong() }, null),
                ColumnValues.Int64(LongArray(n), BooleanArray(n)),
                ColumnValues.Float32(FloatArray(n) { Float.NaN }, null),
                ColumnValues.Binary(offsets, wkb.fold(ByteArray(0)) { a, b -> a + b }, null),
            )
        }
        val out = MemorySink()
        val w = ParquetWriter(out, cols, ParquetCodec.LZ4_RAW, sortedBy = 0, pageIndex = true)
        w.begin()
        w.rowGroup(group(0, 5))
        w.rowGroup(group(5, 4))
        w.finish()
        val bytes = out.toByteArray()
        saveSample("parquet-samples", "writer-pageindex-nulls.parquet", bytes)

        val pf = ParquetFile.wrap(bytes)
        for ((g, rg) in pf.metadata.rowGroups.withIndex()) {
            val (id, none, nan, geom) = rg.columns
            for (c in rg.columns) assertNotNull("rg$g ${c.name} offset index", c.offsetIndex)
            assertNotNull("rg$g id column index", id.columnIndex)
            assertNull("rg$g all NaN has no column index", nan.columnIndex)
            assertNull("rg$g geometry has no column index", geom.columnIndex)
            val ci = ColumnIndex.parse(view(bytes, none.columnIndex ?: fail("rg$g all null has no column index")))
            assertEquals("rg$g null page", listOf(true), ci.nullPages.toList())
            assertEquals("rg$g empty min", 0, ci.minValues[0].size)
            assertEquals("rg$g empty max", 0, ci.maxValues[0].size)
            assertEquals("rg$g null count", listOf(rg.numRows), ci.nullCounts?.toList())
            assertEquals("rg$g unordered", 0, ci.boundaryOrder)
        }
        assertEquals(9, pf.readColumn("none").nullCount)
        assertTrue((pf.readColumn("nan") as ColumnValues.Float32).values.all { it.isNaN() })
        assertEquals(9, pf.readColumn("geom").size)

        val names = cols.map { it.name }
        ParquetReader.open(MemoryRangeSource(bytes)).use { reader ->
            val batch = reader.scan(ScanRequest(names, filterColumn = "id", filterRanges = listOf(3L..6L)))
            assertEquals(4, batch.rowCount)
            assertEquals(listOf<Any?>(3L, 4L, 5L, 6L), rows(batch["id"]!!.toColumnValues()))
            assertEquals(List<Any?>(4) { null }, rows(batch["none"]!!.toColumnValues()))
            assertTrue((batch["nan"]!!.toColumnValues() as ColumnValues.Float32).values.all { it.isNaN() })
            assertEquals(4, batch["geom"]!!.size)
            assertEquals("an all null filter column matches nothing", 0, reader.scan(ScanRequest(names, filterColumn = "none", filterRanges = listOf(0L..0L))).rowCount)
        }
    }

    /** Row values for comparison, floats by their bits so NaN equals NaN. */
    private fun rows(c: ColumnValues): List<Any?> = List(c.size) { i ->
        if (c.isNull(i)) {
            null
        } else {
            when (c) {
                is ColumnValues.Int32 -> c.values[i]
                is ColumnValues.Int64 -> c.values[i]
                is ColumnValues.Float32 -> c.values[i].toRawBits()
                is ColumnValues.Float64 -> c.values[i].toRawBits()
                is ColumnValues.Binary -> c.bytes(i)!!.toList()
            }
        }
    }
}
