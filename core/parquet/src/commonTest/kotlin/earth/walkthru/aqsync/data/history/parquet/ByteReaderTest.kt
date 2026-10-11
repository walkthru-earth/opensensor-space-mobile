package earth.walkthru.aqsync.data.history.parquet

import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * The storage seams: [ByteReader] behind [ParquetFile], the footer-first [ParquetReader] over a
 * [RangeSource], row group statistics for pruning, and the typed accessors. Real fixtures, every platform.
 */
class ByteReaderTest {

    private val hourly = "parquet/v3/v3-hourly-lz4.parquet" // 4 row groups, INT64 TIMESTAMP statistics
    private val device = "parquet/device/v2-none-90rows-unsynced.parquet"

    /** Records every request a [ParquetReader] makes: tail reads and the ranges of each read. */
    private class RecordingSource(bytes: ByteArray) : RangeSource {
        private val inner = MemoryRangeSource(bytes, "remote")
        val tails = mutableListOf<Int>()
        val reads = mutableListOf<List<LongRange>>()
        override suspend fun id(): SourceId = inner.id()
        override suspend fun readTail(n: Int): ByteView = inner.readTail(n).also { tails += n }
        override suspend fun readRanges(ranges: List<LongRange>): List<ByteView> = inner.readRanges(ranges).also { reads += ranges }
    }

    /** A policy that never reads a fixture whole, so the tests see the per-chunk reads. */
    private fun ranged(tail: Int = 64 * 1024) = RangePolicy.LOCAL.copy(wholeFileBelowBytes = 0, tailPrefetchBytes = tail)

    @Test
    fun `byte array reader returns exact ranges and refuses ranges outside the file`() {
        val r = ByteArrayReader(byteArrayOf(1, 2, 3, 4, 5))
        assertEquals(5L, r.size)
        kotlin.test.assertContentEquals(byteArrayOf(2, 3, 4), r.read(1, 3))
        kotlin.test.assertContentEquals(byteArrayOf(), r.read(5, 0))
        for ((offset, length) in listOf(-1L to 1, 4L to 2, 0L to -1)) {
            try { r.read(offset, length); fail("accepted [$offset +$length)") } catch (_: IndexOutOfBoundsException) { }
        }
    }

    @Test
    fun `open over a ByteReader decodes like wrap`() {
        val bytes = fixtureBytes(device)
        val viaReader = ParquetFile.open(ByteArrayReader(bytes), "reader")
        val viaWrap = ParquetFile.wrap(bytes)
        assertEquals(viaWrap.metadata.numRows, viaReader.metadata.numRows)
        for (leaf in viaWrap.leaves) {
            val a = viaWrap.readColumn(leaf)
            val b = viaReader.readColumn(leaf)
            assertEquals("${leaf.name} nulls", a.nullCount, b.nullCount)
            for (i in 0 until a.size) assertEquals("${leaf.name} row $i", a.asDouble(i), b.asDouble(i))
        }
    }

    /** A [ByteArrayReader] that counts its closes. */
    private class CountingReader(bytes: ByteArray) : ByteReader {
        private val inner = ByteArrayReader(bytes)
        var closes = 0
        override val size: Long get() = inner.size
        override fun read(offset: Long, length: Int): ByteArray = inner.read(offset, length)
        override fun close() {
            closes++
        }
    }

    @Test
    fun `a file opened over a ByteReader closes it exactly once`() {
        val bytes = fixtureBytes(device)
        val read = CountingReader(bytes)
        val rows = ParquetFile.open(read, "reader").use { it.metadata.numRows }
        assertEquals(90L, rows)
        assertEquals(1, read.closes)

        val failing = CountingReader(bytes)
        try {
            ParquetFile.open(failing, "reader").use { error("caller failed") }
        } catch (_: IllegalStateException) { }
        assertEquals(1, failing.closes)

        // A footer that does not parse is closed by open itself, before the caller ever holds the file.
        val truncated = CountingReader(bytes.copyOf(bytes.size - 8))
        try {
            ParquetFile.open(truncated, "reader")
            fail("opened a truncated file")
        } catch (_: ParquetFormatException) { }
        assertEquals(1, truncated.closes)
    }

    @Test
    fun `range reader reads the footer from the tail and only the requested chunks`() = runTest {
        val bytes = fixtureBytes(hourly)
        val source = RecordingSource(bytes)
        val remote = ParquetReader.open(source, policy = ranged())
        assertEquals("one tail read covers the footer", 1, source.tails.size)
        assertEquals(0, source.reads.size)
        val local = ParquetFile.wrap(bytes)
        assertEquals(local.metadata.numRows, remote.numRows)
        assertEquals(4, remote.rowGroupCount)

        val time = remote.metadata.leaf("time_ms")!!
        val part = remote.scan(ScanRequest(listOf("time_ms"), rowGroups = listOf(2)))
        val chunk = remote.metadata.rowGroups[2].columns[time.index]
        val chunkRange = byteRange(chunk.firstPageOffset, chunk.totalCompressedSize.toInt())
        if (chunkRange.first < bytes.size - 64 * 1024) {
            assertEquals("one more read for one chunk", listOf(listOf(chunkRange)), source.reads)
        } else {
            assertEquals("the chunk lies inside the tail", 0, source.reads.size)
        }
        assertEquals(1, part.rowGroupsRead)
        kotlin.test.assertContentEquals((local.readColumn(time, 2) as ColumnValues.Int64).values, (part["time_ms"] as PrimitiveColumn.I64).values)

        val all = remote.readColumns(listOf("time_ms", "counter", "no_such_column"))
        kotlin.test.assertContentEquals((local.readColumn(time) as ColumnValues.Int64).values, (all["time_ms"] as PrimitiveColumn.I64).values)
        assertEquals(listOf("time_ms", "counter"), all.columns.keys.toList())
        assertEquals(local.metadata.numRows.toInt(), all.rowCount)
    }

    @Test
    fun `a footer longer than the first tail read is completed with one more read`() = runTest {
        val bytes = fixtureBytes(device)
        val source = RecordingSource(bytes)
        val remote = ParquetReader.open(source, policy = ranged(tail = 64))
        assertEquals(1, source.tails.size)
        assertEquals(1, source.reads.size)
        val local = ParquetFile.wrap(bytes)
        assertEquals(local.metadata.leaves, remote.metadata.leaves)
        assertEquals(local.metadata.keyValueMetadata, remote.metadata.keyValueMetadata)
        val a = local.readColumn("monotonic_us") as ColumnValues.Int64
        val b = remote.readColumns(listOf("monotonic_us"))["monotonic_us"] as PrimitiveColumn.I64
        kotlin.test.assertContentEquals(a.values, b.values)
    }

    @Test
    fun `range reader refuses a file that is not Parquet`() = runTest {
        val notParquet = ByteArray(100) { 7 }
        try {
            ParquetReader.open(MemoryRangeSource(notParquet))
            fail("accepted a non-Parquet file")
        } catch (e: ParquetFormatException) {
            assertTrue(e.message, e.message!!.contains("PAR1"))
        }
    }

    @Test
    fun `INT64 row group statistics prune row groups by value ranges`() {
        val pf = ParquetFile.wrap(fixtureBytes(hourly))
        val stats = pf.metadata.longStatistics("time_ms")
        assertEquals(4, stats.size)
        val decoded = (0 until 4).map { pf.readColumn("time_ms", it) as ColumnValues.Int64 }
        stats.forEachIndexed { g, s ->
            val st = checkNotNull(s) { "rg$g statistics" }
            assertEquals(g, st.rowGroup)
            assertEquals(decoded[g].values.min(), st.min)
            assertEquals(decoded[g].values.max(), st.max)
            assertEquals(decoded[g].size.toLong(), st.numRows)
        }
        val min1 = checkNotNull(stats[1]?.min)
        val max1 = checkNotNull(stats[1]?.max)
        val min3 = checkNotNull(stats[3]?.min)
        val max3 = checkNotNull(stats[3]?.max)
        val min0 = checkNotNull(stats[0]?.min)
        assertEquals(listOf(1), pf.metadata.rowGroupsOverlapping("time_ms", listOf(min1..min1)))
        assertEquals(listOf(1, 3), pf.metadata.rowGroupsOverlapping("time_ms", listOf(max1..max1, min3..max3)))
        assertEquals(emptyList<Int>(), pf.metadata.rowGroupsOverlapping("time_ms", listOf(Long.MIN_VALUE..(min0 - 1))))
        assertEquals(listOf(0, 1, 2, 3), pf.metadata.rowGroupsOverlapping("time_ms", listOf(Long.MIN_VALUE..Long.MAX_VALUE)))
        try { pf.metadata.longStatistics("no_such_column"); fail("unknown column accepted") } catch (_: ParquetFormatException) { }
    }

    @Test
    fun `chunks without statistics are kept by pruning`() {
        // The v1 firmware writer records null_count only, so nothing can be ruled out.
        val pf = ParquetFile.wrap(fixtureBytes("parquet/device/v1-none-90rows-dated.parquet"))
        val leaf = pf.leaves.first { it.type == ParquetType.INT64 }
        assertEquals(List(pf.rowGroupCount) { it }, pf.metadata.rowGroupsOverlapping(leaf.name, listOf(0L..0L)))
    }

    @Test
    fun `typed accessors expose primitive arrays without boxing`() {
        val pf = ParquetFile.wrap(fixtureBytes(device))
        val mono = pf.readColumn("monotonic_us") as ColumnValues.Int64
        kotlin.test.assertSame(mono.values, mono.longValues())
        val pm = pf.readColumn("pm25_atmospheric_ug_m3")
        val asLongs = pm.longValues()
        for (i in 0 until pm.size) if (!pm.isNull(i)) assertEquals(pm.asLong(i), asLongs[i])
        val temp = pf.readColumn("imu_temperature_c")
        val doubles = temp.doubleValues()
        for (i in 0 until temp.size) {
            if (temp.isNull(i)) assertTrue(doubles[i].isNaN()) else assertEquals("row $i", temp.asDouble(i)!!, doubles[i], 0.0)
        }
        try { ColumnValues.Float32(floatArrayOf(1f), null).longValues(); fail("float as long") } catch (_: ParquetFormatException) { }
    }
}
