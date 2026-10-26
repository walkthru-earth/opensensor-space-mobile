package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.archive.ArchiveRecord
import earth.walkthru.aqsync.data.history.parquet.ColumnValues
import earth.walkthru.aqsync.data.history.parquet.ParquetCodec
import earth.walkthru.aqsync.data.history.parquet.ParquetFile
import earth.walkthru.aqsync.data.history.parquet.ParquetLogicalType
import earth.walkthru.aqsync.data.history.parquet.ParquetType
import earth.walkthru.aqsync.data.history.parquet.ParquetWriter
import space.opensensor.model.BoardTemplate
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * #186 acceptance on current format board files. Each file carries the firmware footer keys
 * `dictionary_sha256` and `sample_interval_ms` (aq-parquet `aq_logger_footer.h`) and real column names,
 * and is read through [FileTemplates.BUNDLED], so the bundled CoreS3 and Waveshare templates decide
 * which rows are valid (`valid_when`), which are settling (`settle_s`) and how each column rolls up.
 * An independent loop over the same rows is the oracle for every 15 minute, hourly and daily bucket.
 */
class CurrentBoardFilesTest {
    private val station = "board-station"
    private val day = 20_000L

    /** 10:40:00Z of [day], so the rows cross a 15 minute and an hour boundary. */
    private val startS = day * 86_400 + 10 * 3_600 + 40 * 60
    private val rows = 180

    // Row values, all functions of the row index.
    private fun status(i: Int) = if (i % 17 == 5) 2 else 4
    private fun pm25(i: Int) = i % 23 + 3
    private fun pm1(i: Int) = i % 11 + 1
    private fun pm10(i: Int) = i % 29 + 5
    private fun cf1(i: Int) = i % 19 + 4
    private fun gt03(i: Int) = 100 + 3 * i
    private fun ambient(i: Int) = 20f + (i % 10) * 0.5f
    private fun humidity(i: Int) = if (i % 31 == 7) Float.NaN else 40f + i % 7
    private fun battery(i: Int) = 100 - i / 10

    /** Power on 60 s before the first row, so rows with a monotonic time under 180 s are settling. */
    private fun monoUs(i: Int) = (60L + 10L * i) * 1_000_000L

    /**
     * A current format telemetry file of [template], one row every 10 s from [startS] (shifted by
     * [offsetS]), every [stepRows] row, footer interval [intervalMs] and boot [boot].
     */
    private fun boardFile(template: BoardTemplate, intervalMs: Long = 10_000, boot: Long = 1, offsetS: Long = 0, stepRows: Int = 1): ByteArray {
        val idx = (0 until rows step stepRows).toList()
        val n = idx.size
        val sink = MemorySink()
        val w = ParquetWriter(
            sink,
            listOf(
                ParquetWriter.Column("boot_id_hi", ParquetType.INT64),
                ParquetWriter.Column("boot_id_lo", ParquetType.INT64),
                ParquetWriter.Column(ParquetIndexer.SEQ, ParquetType.INT64),
                ParquetWriter.Column(ParquetIndexer.MONO, ParquetType.INT64),
                ParquetWriter.Column(UtcColumns.EVENT_US, ParquetType.INT64, timestampUtc = ParquetLogicalType.TimeUnit.MICROS),
                ParquetWriter.Column("pms_status", ParquetType.INT32),
                ParquetWriter.Column("pm1_atmospheric_ug_m3", ParquetType.INT32),
                ParquetWriter.Column(Metrics.PM25.column, ParquetType.INT32),
                ParquetWriter.Column("pm10_atmospheric_ug_m3", ParquetType.INT32),
                ParquetWriter.Column(Metrics.PM25_CORRECTED.column, ParquetType.INT32),
                ParquetWriter.Column("particles_gt03_per_01l", ParquetType.INT32),
                ParquetWriter.Column("ambient_temperature_c", ParquetType.FLOAT),
                ParquetWriter.Column("relative_humidity_percent", ParquetType.FLOAT),
                ParquetWriter.Column("battery_percent", ParquetType.INT32),
            ),
            ParquetCodec.UNCOMPRESSED,
            keyValueMetadata = mapOf(
                "schema_version" to "5",
                FileTemplates.DICTIONARY_KEY to template.dict,
                FileTemplates.SAMPLE_INTERVAL_KEY to intervalMs.toString(),
                "row_group_rows_max" to "90",
                "purpose" to "telemetry",
            ),
        )
        w.begin()
        w.rowGroup(
            listOf(
                ColumnValues.Int64(LongArray(n) { 0L }, null),
                ColumnValues.Int64(LongArray(n) { boot }, null),
                ColumnValues.Int64(LongArray(n) { idx[it].toLong() }, null),
                ColumnValues.Int64(LongArray(n) { monoUs(idx[it]) }, null),
                ColumnValues.Int64(LongArray(n) { (startS + offsetS + 10L * idx[it]) * 1_000_000L }, null),
                ColumnValues.Int32(IntArray(n) { status(idx[it]) }, null),
                ColumnValues.Int32(IntArray(n) { pm1(idx[it]) }, null),
                ColumnValues.Int32(IntArray(n) { pm25(idx[it]) }, null),
                ColumnValues.Int32(IntArray(n) { pm10(idx[it]) }, null),
                ColumnValues.Int32(IntArray(n) { cf1(idx[it]) }, null),
                ColumnValues.Int32(IntArray(n) { gt03(idx[it]) }, null),
                ColumnValues.Float32(FloatArray(n) { ambient(idx[it]) }, null),
                ColumnValues.Float32(FloatArray(n) { humidity(idx[it]) }, null),
                ColumnValues.Int32(IntArray(n) { battery(idx[it]) }, null),
            ),
        )
        w.finish()
        return sink.toByteArray()
    }

    private fun buckets(vararg files: ByteArray): List<Bucket> =
        Aggregator(station, templates = FileTemplates.BUNDLED).apply { files.forEach { add(ParquetFile.wrap(it)) } }.buckets()

    /** What one metric should hold in one bucket, from the rows alone. */
    private class Want(val values: List<Float>, val settling: Int, val point: Boolean = false) {
        val agg: MetricAgg? get() = if (values.isEmpty()) {
            null
        } else {
            MetricAgg(values.size, if (point) values.last() else (values.sumOf { it.toDouble() } / values.size).toFloat(), values.min(), values.max(), settling)
        }
    }

    /** The oracle. PM and particle columns count when `pms_status` is 4 and settle for 180 s, the rest count when finite. */
    private fun expected(metric: MetricSpec, picked: List<Int>): MetricAgg? {
        val pmsValid = picked.filter { status(it) == 4 }
        val settling = pmsValid.count { monoUs(it) < 180_000_000L }
        fun pms(f: (Int) -> Int) = Want(pmsValid.map { f(it).toFloat() }, settling).agg
        return when (metric.id) {
            Metrics.PM25.id -> pms(::pm25)
            Metrics.PM1.id -> pms(::pm1)
            Metrics.PM10.id -> pms(::pm10)
            "pm25_cf1_ug_m3" -> pms(::cf1)
            Metrics.PARTICLES_GT03.id -> pms(::gt03)
            Metrics.PM25_CORRECTED.id -> {
                val ok = pmsValid.mapNotNull { i -> humidity(i).takeIf { it.isFinite() }?.let { rh -> Metrics.PM25_CORRECTED.valueOf(cf1(i).toDouble(), rh.toDouble()) }?.let { i to it.toFloat() } }
                Want(ok.map { it.second }, ok.count { monoUs(it.first) < 180_000_000L }).agg
            }
            Metrics.AMBIENT_TEMP.id -> Want(picked.map(::ambient), 0).agg
            Metrics.HUMIDITY.id -> Want(picked.map(::humidity).filter { it.isFinite() }, 0).agg
            "battery_percent" -> Want(picked.map { battery(it).toFloat() }, 0, point = true).agg
            else -> null
        }
    }

    private fun assertBucketsMatchTheRows(got: List<Bucket>, intervalMs: Long, stepRows: Int = 1) {
        val all = (0 until rows step stepRows).toList()
        for (res in Resolution.entries) {
            val byStart = all.groupBy { res.bucketStart(startS + 10L * it) }
            val mine = got.filter { it.resolution == res }
            assertEquals("$res bucket starts", byStart.keys.toList(), mine.map { it.startEpochS })
            for (bucket in mine) {
                val picked = byStart.getValue(bucket.startEpochS)
                assertEquals("$res rows", picked.size, bucket.rows)
                assertEquals("$res interval", intervalMs, bucket.sampleIntervalMs)
                assertEquals("$res expected rows", res.seconds * 1000 / intervalMs, bucket.expectedRows)
                val ids = bucket.metrics.keys
                for (id in ids) assertTrue(expected(Metrics.byId(id)!!, picked) != null, "$res $id has values it should not")
                for (metric in Metrics.all) assertEquals("$res ${metric.id} at ${bucket.startEpochS}", expected(metric, picked), bucket.agg(metric))
            }
        }
    }

    @Test
    fun aCoreS3FileGivesTheBucketsItsTemplateDefines() {
        val got = buckets(boardFile(coreS3Template))
        assertEquals(listOf(3, 2, 1), Resolution.entries.map { r -> got.count { it.resolution == r } })
        assertBucketsMatchTheRows(got, 10_000)
        // The fixture exercises the gate, the settle window and a point column.
        val day = got.single { it.resolution == Resolution.DAY }
        assertTrue(day.agg(Metrics.PM25)!!.n < rows && day.agg(Metrics.PM25)!!.settling > 0)
        assertEquals(battery(rows - 1).toFloat(), day.metrics.getValue("battery_percent").mean)
    }

    @Test
    fun aWaveshareFileGivesTheBucketsItsTemplateDefines() {
        assertBucketsMatchTheRows(buckets(boardFile(waveshareTemplate)), 10_000)
    }

    @Test
    fun theSameRowsGiveTheSameBucketsOnBothBoards() {
        assertEquals(buckets(boardFile(coreS3Template)), buckets(boardFile(waveshareTemplate)))
    }

    @Test
    fun theFooterIntervalSetsTheExpectedRows() {
        // A logger at 30 s writes every third row. The footer, not the template's 10 s default, sets the expected rows.
        val got = buckets(boardFile(waveshareTemplate, intervalMs = 30_000, stepRows = 3))
        assertBucketsMatchTheRows(got, 30_000, stepRows = 3)
        assertEquals(listOf(30L, 120L, 2880L), Resolution.entries.map { r -> got.first { it.resolution == r }.expectedRows })
    }

    @Test
    fun aBucketWithTwoIntervalsKeepsTheShortest() {
        // Two boots in one bucket, one at 10 s and one at 60 s. The bucket keeps 10 s whichever file comes first.
        val fast = boardFile(coreS3Template, intervalMs = 10_000, boot = 1)
        val slow = boardFile(coreS3Template, intervalMs = 60_000, boot = 2, offsetS = 5, stepRows = 6)
        for (order in listOf(listOf(fast, slow), listOf(slow, fast))) {
            val got = buckets(*order.toTypedArray())
            assertTrue(got.isNotEmpty())
            assertTrue(got.all { it.sampleIntervalMs == 10_000L }, got.map { it.sampleIntervalMs }.toString())
        }
    }

    private fun dailyOf(vararg files: ByteArray): ByteArray {
        val sources = files.mapIndexed { k, bytes ->
            val index = ParquetIndexer.index(ParquetFile.wrap(bytes), templates = FileTemplates.BUNDLED)
            val record = ArchiveRecord(
                station, "f$k.parquet", bytes.size.toLong(), 1, "sha-$k", 1, "fixture", indexedAtEpochMs = 1, rows = index.rows,
                bootId = index.bootId, utcRows = index.utcRows, firstUtcNs = index.firstUtcNs, lastUtcNs = index.lastUtcNs,
            )
            DailyParquetSource(record) { ParquetFile.wrap(bytes) }
        }
        val sink = MemorySink()
        assertNotNull(DailyParquetBuilder.build(station, day, sources, sink, emptyMap(), 0))
        return sink.toByteArray()
    }

    @Test
    fun aDailyFileOfTwoIntervalsWritesTheShortest() {
        val fast = boardFile(coreS3Template, intervalMs = 10_000, boot = 1)
        val slow = boardFile(coreS3Template, intervalMs = 60_000, boot = 2, offsetS = 5, stepRows = 6)
        fun interval(daily: ByteArray) = ParquetFile.wrap(daily).metadata.keyValueMetadata[FileTemplates.SAMPLE_INTERVAL_KEY]
        assertEquals("60000", interval(dailyOf(slow)))
        for (order in listOf(arrayOf(fast, slow), arrayOf(slow, fast))) {
            val daily = dailyOf(*order)
            assertEquals("10000", interval(daily))
            // Read back, the daily file gives the same interval as the tiers of its sources.
            assertTrue(buckets(daily).all { it.sampleIntervalMs == 10_000L })
        }
    }
}
