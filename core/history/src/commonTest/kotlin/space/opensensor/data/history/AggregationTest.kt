package space.opensensor.data.history

import space.opensensor.airquality.EpaPm25Correction
import space.opensensor.data.history.parquet.ColumnValues
import space.opensensor.data.history.parquet.ParquetCodec
import space.opensensor.data.history.parquet.ParquetFile
import space.opensensor.data.history.parquet.ParquetLogicalType
import space.opensensor.data.history.parquet.ParquetType
import space.opensensor.data.history.parquet.ParquetWriter
import kotlin.math.abs
import kotlin.test.Test

class AggregationTest {

    private fun bytes(path: String) = fixtureBytes("parquet/$path")

    private val thing = "53315f5f-cb85-4d8d-b623-d56266084189"

    /** 2026-09-17 09:42:35Z … 09:59:55Z, 105 rows, all PMS-valid. */
    private val boardFile get() = ParquetFile.wrap(bytes("device/v3-board-2rg-105rows.parquet"))

    @Test
    fun `board file lands in the right 15-minute and hourly and daily buckets`() {
        val agg = Aggregator(thing, templates = CoreS3Files)
        agg.add(boardFile)
        val buckets = agg.buckets()
        val q = buckets.filter { it.resolution == Resolution.MIN15 }
        val h = buckets.filter { it.resolution == Resolution.HOUR }
        val d = buckets.filter { it.resolution == Resolution.DAY }
        // 09:30 bucket holds 09:42:35–09:44:55 (15 rows), 09:45 bucket holds 09:45:05–09:59:55 (90 rows).
        assertEquals(listOf(1_789_637_400L, 1_789_638_300L), q.map { it.startEpochS })
        assertEquals(listOf(15, 90), q.map { it.rows })
        assertEquals(listOf(15L, 90L), q.map { it.agg(AirMetrics.PM25)!!.n.toLong() })
        assertEquals(1, h.size)
        assertEquals(1_789_635_600L, h[0].startEpochS) // 09:00Z
        assertEquals(105, h[0].rows)
        assertEquals(1, d.size)
        assertEquals(1_789_603_200L, d[0].startEpochS) // 2026-09-17T00:00Z
        assertEquals(105, d[0].rows)
        // Means agree with PyArrow (pm25 mean 27.9333, imu temperature mean 41.4009 over the file).
        assertEquals(27.9333, d[0].agg(AirMetrics.PM25)!!.mean.toDouble(), 0.001)
        assertEquals(41.4009, d[0].agg(AirMetrics.BOARD_TEMP)!!.mean.toDouble(), 0.001)
        assertTrue(d[0].agg(AirMetrics.PM25)!!.min <= d[0].agg(AirMetrics.PM25)!!.max)
        assertEquals(105f / 8640f, d[0].coverage(AirMetrics.PM25), 1e-6f)
        assertEquals(1f, q[1].coverage(AirMetrics.PM25), 1e-6f)
        // Registry entries whose column is null on this board (SHT20) simply have no aggregate — never a zero.
        assertNull(d[0].agg(AirMetrics.HUMIDITY))
        // No RH on this board, so no corrected PM2.5. The cards fall back to raw, labelled raw.
        assertNull(d[0].agg(AirMetrics.PM25_CORRECTED))
        assertNull(d[0].agg(AirMetrics.AMBIENT_TEMP))
        assertNotNull(d[0].agg(AirMetrics.BATTERY))
        // The board booted 38 s before its first row: the first 180 s of uptime are "settling" — 15 rows (38 s → 180 s).
        assertEquals(15, d[0].agg(AirMetrics.PM25)!!.settling)
        assertEquals(0, d[0].agg(AirMetrics.BOARD_TEMP)!!.settling) // no settle window on the IMU
    }

    @Test
    fun `rows without UTC or without a valid PMS reading are handled`() {
        // A tiny file written with our own writer: 4 rows, the third without UTC, the second with a stale sensor.
        val out = MemorySink()
        val w = ParquetWriter(
            out,
            listOf(
                ParquetWriter.Column("event_time_utc_ns", ParquetType.INT64, timestampUtc = ParquetLogicalType.TimeUnit.NANOS),
                ParquetWriter.Column("pms_status", ParquetType.INT32),
                ParquetWriter.Column("pm25_atmospheric_ug_m3", ParquetType.INT32),
                ParquetWriter.Column("imu_temperature_c", ParquetType.FLOAT),
            ),
            ParquetCodec.UNCOMPRESSED,
        )
        w.begin()
        w.rowGroup(
            listOf(
                ColumnValues.Int64(longArrayOf(3_600_000_000_000L, 3_610_000_000_000L, 0L, 3_620_000_000_000L), booleanArrayOf(true, true, false, true)),
                ColumnValues.Int32(intArrayOf(4, 2, 4, 4), null),
                ColumnValues.Int32(intArrayOf(10, 99, 50, 20), null),
                ColumnValues.Float32(floatArrayOf(40f, 41f, 42f, Float.NaN), null),
            ),
        )
        w.finish()
        val agg = Aggregator(thing, setOf(Resolution.HOUR), templates = CoreS3Files)
        agg.add(ParquetFile.wrap(out.toByteArray()))
        val b = agg.buckets().single()
        assertEquals(3600L, b.startEpochS)
        assertEquals(3, b.rows) // the UTC-less row is not on the timeline at all
        val pm = b.agg(AirMetrics.PM25)!!
        assertEquals(2, pm.n) // the stale row counts as a row but not as a reading
        assertEquals(15f, pm.mean)
        assertEquals(10f, pm.min)
        assertEquals(20f, pm.max)
        assertEquals(40.5f, b.agg(AirMetrics.BOARD_TEMP)!!.mean) // NaN skipped
        assertNull(b.agg(AirMetrics.BATTERY)) // column absent from this file
    }

    @Test
    fun `corrected pm25 is the mean of corrected rows and never the corrected mean`() {
        val agg = Aggregator(thing, setOf(Resolution.HOUR), templates = CoreS3Files)
        agg.add(ParquetFile.wrap(humidPm25Parquet()))
        val b = agg.buckets().single()
        assertEquals(HUMID_HOUR_S, b.startEpochS)
        val epa = b.agg(AirMetrics.PM25_CORRECTED)!!
        // Row 3 has a null RH, row 4 an RH of 120 and row 5 a stale sensor, so two rows count.
        assertEquals(2, epa.n)
        val a = EpaPm25Correction.correct(10.0, 50.0)!!
        val c = EpaPm25Correction.correct(250.0, 80.0)!!
        assertEquals(((a + c) / 2).toFloat(), epa.mean, 1e-3f)
        assertEquals(a.toFloat(), epa.min, 1e-3f)
        assertEquals(c.toFloat(), epa.max, 1e-3f)
        val ofMean = EpaPm25Correction.correct(130.0, 65.0)!!
        assertTrue("corrected rows $epa, corrected mean $ofMean", abs(epa.mean - ofMean) > 5)
        // Stored values never change, the raw aggregate still reads the atmospheric column.
        val raw = b.agg(AirMetrics.PM25)!!
        assertEquals(4, raw.n)
        assertEquals(60f, raw.mean)
    }

    @Test
    fun `file summaries correct each row too`() {
        val summary = FileSummary.of("humid.parquet", ParquetFile.wrap(humidPm25Parquet()), templates = CoreS3Files)
        val a = EpaPm25Correction.correct(10.0, 50.0)!!
        val c = EpaPm25Correction.correct(250.0, 80.0)!!
        assertEquals(((a + c) / 2).toFloat(), summary.metrics.getValue(AirMetrics.PM25_CORRECTED.id).mean, 1e-3f)
        assertEquals(60f, summary.metrics.getValue(AirMetrics.PM25.id).mean)
    }

    @Test
    fun `unsynced file contributes nothing`() {
        val agg = Aggregator(thing, templates = CoreS3Files)
        agg.add(ParquetFile.wrap(bytes("device/v2-none-90rows-unsynced.parquet")))
        assertTrue(agg.buckets().isEmpty())
    }
}
