package earth.walkthru.aqsync.data.history.parquet

import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * The storage seam: [ByteReader], the footer-first [SuspendParquetFile] that a remote range source will
 * use, row group statistics for pruning, and the typed accessors. Real fixtures, every platform.
 */
class ByteReaderTest {

    private val hourly = "parquet/v3/v3-hourly-lz4.parquet" // 4 row groups, INT64 TIMESTAMP statistics
    private val device = "parquet/device/v2-none-90rows-unsynced.parquet"

    /** Records every range read, so a test can prove what was (and was not) fetched. */
    private class CountingReader(private val bytes: ByteArray) : SuspendByteReader {
        val reads = mutableListOf<Pair<Long, Int>>()
        override suspend fun size(): Long = bytes.size.toLong()
        override suspend fun read(offset: Long, length: Int): ByteArray {
            reads += offset to length
            return ByteArrayReader(bytes).read(offset, length)
        }
    }

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

    @Test
    fun `suspend file reads the footer from the tail and only the requested chunks`() = runTest {
        val bytes = fixtureBytes(hourly)
        val source = CountingReader(bytes)
        val remote = SuspendParquetFile.open(source, "remote")
        assertEquals("one tail read covers the footer", 1, source.reads.size)
        assertEquals(bytes.size.toLong(), source.reads.single().first + source.reads.single().second)
        val local = ParquetFile.wrap(bytes)
        assertEquals(local.metadata.numRows, remote.numRows)
        assertEquals(4, remote.rowGroupCount)

        val time = remote.leaf("time_ms")
        val part = remote.readColumn(time, 2) as ColumnValues.Int64
        assertEquals("one more read for one chunk", 2, source.reads.size)
        val chunk = remote.metadata.rowGroups[2].columns[time.index]
        assertEquals(chunk.firstPageOffset to chunk.totalCompressedSize.toInt(), source.reads.last())
        kotlin.test.assertContentEquals((local.readColumn(time, 2) as ColumnValues.Int64).values, part.values)

        val all = remote.readColumn(time) as ColumnValues.Int64
        kotlin.test.assertContentEquals((local.readColumn(time) as ColumnValues.Int64).values, all.values)
        val two = remote.readColumns(listOf("time_ms", "counter"), 0)
        assertEquals(listOf("time_ms", "counter"), two.keys.toList())
    }

    @Test
    fun `a footer longer than the first tail read is completed with one more read`() = runTest {
        val bytes = fixtureBytes(device)
        val source = CountingReader(bytes)
        val remote = SuspendParquetFile.open(source, "remote", tailBytes = 64)
        assertEquals(2, source.reads.size)
        val local = ParquetFile.wrap(bytes)
        assertEquals(local.metadata.leaves, remote.metadata.leaves)
        assertEquals(local.metadata.keyValueMetadata, remote.metadata.keyValueMetadata)
        val a = local.readColumn("monotonic_us") as ColumnValues.Int64
        val b = remote.readColumn(remote.leaf("monotonic_us")) as ColumnValues.Int64
        kotlin.test.assertContentEquals(a.values, b.values)
    }

    @Test
    fun `suspend open refuses a file that is not Parquet`() = runTest {
        val notParquet = ByteArray(100) { 7 }
        try {
            SuspendParquetFile.open(ByteArrayReader(notParquet).asSuspend())
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
