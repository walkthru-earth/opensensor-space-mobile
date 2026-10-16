package earth.walkthru.aqsync.data.history.parquet

import kotlin.random.Random
import kotlin.test.Test

/**
 * The writer against the app's own reader, plus sample files under `app/build/parquet-samples/` for
 * the external oracle (`tools/validate_parquet_samples.py`, PyArrow + DuckDB in the firmware repo's
 * pixi environment). The samples are regenerated on every run, so the validator always sees the
 * current writer.
 */
class ParquetWriterTest {

    private fun columns() = listOf(
        ParquetWriter.Column("event_time_utc_ns", ParquetType.INT64, timestampUtc = ParquetLogicalType.TimeUnit.NANOS),
        ParquetWriter.Column("sequence", ParquetType.INT64),
        ParquetWriter.Column("pms_status", ParquetType.INT32),
        ParquetWriter.Column("temperature", ParquetType.FLOAT),
    )

    /** Three row groups: a full one, one with nulls and NaN, one with ±0.0 and negative values. */
    private fun groups(): List<List<ColumnValues>> {
        val t0 = 1_789_638_155_000_000_000L
        val g1 = listOf<ColumnValues>(
            ColumnValues.Int64(LongArray(90) { t0 + it * 10_000_000_000L }, null),
            ColumnValues.Int64(LongArray(90) { it.toLong() }, null),
            ColumnValues.Int32(IntArray(90) { 4 }, null),
            ColumnValues.Float32(FloatArray(90) { 40f + it / 10f }, null),
        )
        val valid = BooleanArray(20) { it % 3 != 0 }
        val g2 = listOf<ColumnValues>(
            ColumnValues.Int64(LongArray(20) { t0 + (90 + it) * 10_000_000_000L }, null),
            ColumnValues.Int64(LongArray(20) { 90L + it }, null),
            ColumnValues.Int32(IntArray(20) { if (valid[it]) 4 else 0 }, valid),
            ColumnValues.Float32(FloatArray(20) { if (it == 5) Float.NaN else 20f - it }, valid),
        )
        val g3 = listOf<ColumnValues>(
            ColumnValues.Int64(LongArray(5) { t0 + (110 + it) * 10_000_000_000L }, null),
            ColumnValues.Int64(LongArray(5) { 110L + it }, null),
            ColumnValues.Int32(intArrayOf(-2_147_483_648, -1, 0, 1, 2_147_483_647), null),
            ColumnValues.Float32(floatArrayOf(-0.0f, 0.0f, -1.5f, 1.5f, Float.NaN), null),
        )
        return listOf(g1, g2, g3)
    }

    private fun write(codec: ParquetCodec): ByteArray {
        val out = MemorySink()
        val w = ParquetWriter(out, columns(), codec, mapOf("station_id" to "test", "purpose" to "writer-test"), "AQ Sync Android writer-test", sortedBy = 0)
        w.begin()
        groups().forEach { w.rowGroup(it) }
        w.finish()
        return out.toByteArray()
    }

    @Test
    fun `round trip through the reader with both codecs and statistics that follow the 2_13 float rules`() {
        for (codec in listOf(ParquetCodec.UNCOMPRESSED, ParquetCodec.LZ4_RAW)) {
            val bytes = write(codec)
            saveSample("parquet-samples", "writer-${codec.name.lowercase()}.parquet", bytes)
            val pf = ParquetFile.wrap(bytes, codec.name)
            val md = pf.metadata
            assertEquals(115L, pf.numRows)
            assertEquals(3, pf.rowGroupCount)
            assertEquals(listOf(90L, 20L, 5L), md.rowGroups.map { it.numRows })
            assertEquals(4, md.columnOrders.size)
            assertEquals(ParquetLogicalType.TimeUnit.NANOS, pf.leaf("event_time_utc_ns").timestampUtcUnit)
            assertEquals(null, pf.leaf("sequence").timestampUtcUnit)
            assertEquals("test", md.stationId)
            assertEquals(listOf(0), md.rowGroups[0].sortingColumns.map { it.columnIdx })
            md.rowGroups.forEachIndexed { g, rg ->
                assertEquals(g, rg.ordinal)
                assertEquals(rg.columns.first().pageOffset(), rg.fileOffset)
                rg.columns.forEach { c -> assertEquals(codec, c.codec) }
            }

            // Values, per group and concatenated.
            val expected = groups()
            for (g in expected.indices) {
                for ((i, col) in pf.leaves.withIndex()) {
                    val got = pf.readColumn(col, g)
                    val want = expected[g][i]
                    assertEquals("$codec rg$g '${col.name}' size", want.size, got.size)
                    for (r in 0 until want.size) {
                        assertEquals("$codec rg$g '${col.name}' null@$r", want.isNull(r), got.isNull(r))
                        if (!want.isNull(r)) {
                            when (want) {
                                is ColumnValues.Float32 -> assertEquals(want.values[r].toBits(), (got as ColumnValues.Float32).values[r].toBits())
                                else -> assertEquals(want.asLong(r), got.asLong(r))
                            }
                        }
                    }
                }
            }
            val seq = pf.readColumn("sequence") as ColumnValues.Int64
            assertEquals(115, seq.size)
            assertEquals(114L, seq.values.last())

            // Statistics: group 2 — pms_status nulls at i % 3 == 0 (7 of 20), temperature NaN excluded and counted.
            val rg2 = md.rowGroups[1]
            val pms = rg2.columns[2].statistics!!
            assertEquals(7L, pms.nullCount)
            assertEquals(4L, pms.minLong(ParquetType.INT32))
            assertEquals(4L, pms.maxLong(ParquetType.INT32))
            val temp = rg2.columns[3].statistics!!
            assertEquals(7L, temp.nullCount)
            assertEquals(1L, temp.nanCount)
            assertEquals(20f - 19f, temp.minFloat())
            assertEquals(20f - 1f, temp.maxFloat()) // index 0 is null; the first valid is 1
            // Group 3: ±0.0 normalised (min -0.0, max +0.0 would only apply if 0 were the extreme; here -1.5 / 1.5), NaN counted.
            val t3 = md.rowGroups[2].columns[3].statistics!!
            assertEquals(1L, t3.nanCount)
            assertEquals(-1.5f, t3.minFloat())
            assertEquals(1.5f, t3.maxFloat())
            val i3 = md.rowGroups[2].columns[2].statistics!!
            assertEquals(Int.MIN_VALUE.toLong(), i3.minLong(ParquetType.INT32))
            assertEquals(Int.MAX_VALUE.toLong(), i3.maxLong(ParquetType.INT32))
            assertEquals(true, i3.isMinValueExact)
            assertNotNull(md.createdBy)
        }
    }

    @Test
    fun `zero as the extreme is written as negative zero for min and positive zero for max`() {
        val out = MemorySink()
        val w = ParquetWriter(out, listOf(ParquetWriter.Column("f", ParquetType.FLOAT)), ParquetCodec.UNCOMPRESSED)
        w.begin()
        w.rowGroup(listOf(ColumnValues.Float32(floatArrayOf(0.0f, 0.0f, -0.0f), null)))
        w.finish()
        val s = ParquetFile.wrap(out.toByteArray()).metadata.rowGroups[0].columns[0].statistics!!
        assertEquals((-0.0f).toBits(), s.minFloat()!!.toBits())
        assertEquals(0.0f.toBits(), s.maxFloat()!!.toBits())
    }

    @Test
    fun `all null and empty columns carry null_count and no min or max`() {
        val out = MemorySink()
        val w = ParquetWriter(out, listOf(ParquetWriter.Column("a", ParquetType.INT64), ParquetWriter.Column("b", ParquetType.FLOAT)), ParquetCodec.LZ4_RAW)
        w.begin()
        w.rowGroup(listOf(ColumnValues.Int64(LongArray(3), BooleanArray(3)), ColumnValues.Float32(floatArrayOf(Float.NaN, Float.NaN, Float.NaN), null)))
        w.rowGroup(emptyList<ColumnValues>().let { listOf(ColumnValues.Int64(LongArray(0), null), ColumnValues.Float32(FloatArray(0), null)) }) // skipped
        w.finish()
        val bytes = out.toByteArray()
        saveSample("parquet-samples", "writer-nulls.parquet", bytes)
        val pf = ParquetFile.wrap(bytes)
        assertEquals(1, pf.rowGroupCount)
        val a = pf.metadata.rowGroups[0].columns[0].statistics!!
        assertEquals(3L, a.nullCount)
        assertNull(a.minValue)
        val b = pf.metadata.rowGroups[0].columns[1].statistics!!
        assertEquals(0L, b.nullCount)
        assertEquals(3L, b.nanCount)
        assertNull(b.minValue) // all NaN: no min/max
        assertEquals(3, pf.readColumn("a").nullCount)
        assertTrue((pf.readColumn("b") as ColumnValues.Float32).values.all { it.isNaN() })
    }

    @Test
    fun `no rows at all is a valid empty file`() {
        val out = MemorySink()
        val w = ParquetWriter(out, columns(), ParquetCodec.LZ4_RAW)
        w.begin(); w.finish()
        val pf = ParquetFile.wrap(out.toByteArray())
        assertEquals(0L, pf.numRows)
        assertEquals(0, pf.readColumn("sequence").size)
    }

    @Test
    fun `field ids and the TIMESTAMP unit round trip and columns without an id read as none`() {
        val out = MemorySink()
        val columns = listOf(
            ParquetWriter.Column("event_time_utc_us", ParquetType.INT64, timestampUtc = ParquetLogicalType.TimeUnit.MICROS, fieldId = 9),
            ParquetWriter.Column("event_time_utc_ns", ParquetType.INT64, timestampUtc = ParquetLogicalType.TimeUnit.NANOS),
            ParquetWriter.Column("sequence", ParquetType.INT64, fieldId = 3),
            ParquetWriter.Column("source_file_index", ParquetType.INT32),
        )
        val w = ParquetWriter(out, columns)
        w.begin()
        w.rowGroup(
            listOf(
                ColumnValues.Int64(longArrayOf(1_789_638_155_000_000L), null),
                ColumnValues.Int64(longArrayOf(1_789_638_155_000_000_000L), null),
                ColumnValues.Int64(longArrayOf(7L), null),
                ColumnValues.Int32(intArrayOf(0), null),
            ),
        )
        w.finish()
        val pf = ParquetFile.wrap(out.toByteArray())
        assertEquals(listOf(9, null, 3, null), pf.leaves.map { it.fieldId })
        assertEquals(ParquetLogicalType.TimeUnit.MICROS, pf.leaf("event_time_utc_us").timestampUtcUnit)
        assertEquals(ParquetLogicalType.TimeUnit.NANOS, pf.leaf("event_time_utc_ns").timestampUtcUnit)
        assertNull(pf.leaf("sequence").timestampUtcUnit)
        assertEquals(1_789_638_155_000_000L, pf.readColumn("event_time_utc_us").asLong(0))
    }

    @Test
    fun `LZ4 block compressor round trips through the decoder`() {
        val rnd = Random(7)
        val inputs = listOf(
            ByteArray(0),
            ByteArray(1) { 42 },
            ByteArray(11) { 1 },
            ByteArray(12) { 1 },
            ByteArray(13) { 1 },
            ByteArray(4096) { (it % 7).toByte() }, // highly repetitive
            ByteArray(5000) { rnd.nextInt().toByte() }, // incompressible
            ByteArray(70_000) { (it / 100).toByte() }, // long matches, offsets > 255
            TestBytes().also { b -> repeat(90) { b.putLong(1_789_638_155_000_000_000L + it * 10_000_000_000L) } }.toByteArray(), // a PLAIN INT64 page
        )
        for (src in inputs) {
            val packed = Lz4Block.compress(src)
            val back = Lz4Block.decompress(packed, src.size)
            assertArrayEquals("size ${src.size}", src, back)
        }
        // And it actually compresses the repetitive cases.
        assertTrue(Lz4Block.compress(ByteArray(4096) { (it % 7).toByte() }).size < 200)
    }

    private fun ColumnChunkMeta.pageOffset() = firstPageOffset
}
