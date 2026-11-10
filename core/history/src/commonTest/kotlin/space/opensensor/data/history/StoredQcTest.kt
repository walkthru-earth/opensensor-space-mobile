package space.opensensor.data.history

import space.opensensor.archive.ArchiveRecord
import space.opensensor.data.history.parquet.ColumnValues
import space.opensensor.data.history.parquet.ParquetFile
import space.opensensor.family.air.AirFamily
import space.opensensor.family.air.AirMetrics
import space.opensensor.family.air.AirQc
import space.opensensor.model.RowCheck
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The stored row checks of #91. The daily builder writes each row's flags in the `qc_flags` column
 * beside the raw columns, which it never changes, and rolls them into the `~qc` and `~clean` tier
 * companions. The check id is part of the day signature, so a rule change rebuilds the derived tier.
 */
class StoredQcTest {
    init {
        AirFamily.install()
    }

    /** Flags with bit 4 every row whose atmospheric PM2.5 is above 100. */
    private object Above100 : RowCheck {
        override val id = "test-above-100-v1"
        override val columns = listOf(AirMetrics.PM25.column)
        override fun flags(timeMs: LongArray, values: List<DoubleArray>) = IntArray(timeMs.size) { if (values[0][it] > 100.0) 4 else 0 }
    }

    private val above100 = RowChecks { Above100 }

    private fun record(bytes: ByteArray, name: String = "humid.parquet"): ArchiveRecord {
        val file = ParquetFile.wrap(bytes)
        val index = ParquetIndexer.index(file, templates = CoreS3Files)
        return ArchiveRecord(
            file.metadata.footer?.thingId ?: "thing-a", name, bytes.size.toLong(), 123, "hash", 1, "device",
            indexedAtEpochMs = 1, rows = index.rows, bootId = index.bootId, utcRows = index.utcRows,
            firstUtcNs = index.firstUtcNs, lastUtcNs = index.lastUtcNs,
            firstMonoUs = index.firstMonoUs, lastMonoUs = index.lastMonoUs,
        )
    }

    private class Day(val pass: DayPassResult, val file: ParquetFile) {
        fun hour(id: String): MetricAgg? = pass.buckets.single { it.resolution == Resolution.HOUR }.metrics[id]
    }

    private fun build(bytes: ByteArray, checks: RowChecks): Day {
        val record = record(bytes)
        val day = record.firstUtcNs!! / 86_400_000_000_000L
        val sink = MemorySink()
        val pass = DayPass.run(record.thing, day, listOf(DailyParquetSource(record) { ParquetFile.wrap(bytes) }), sink, nowEpochS = 0, templates = CoreS3Files, checks = checks)
        assertNotNull(pass.daily)
        return Day(pass, ParquetFile.wrap(sink.toByteArray()))
    }

    @Test
    fun theDayFileStoresEachRowsFlagsBesideTheUnchangedValues() {
        val day = build(humidPm25Parquet(), above100)
        val flags = day.file.readColumn(RowChecks.FLAGS_COLUMN) as ColumnValues.Int32
        assertContentEquals(intArrayOf(0, 4, 0, 0, 0), flags.values)
        assertEquals(Above100.id, day.file.metadata.keyValueMetadata[RowChecks.CHECKS_KEY])
        // The raw columns stay as measured, the flagged 170 included.
        val source = ParquetFile.wrap(humidPm25Parquet())
        for (name in listOf(AirMetrics.PM25.column, "pm25_cf1_ug_m3", "relative_humidity_percent", "pms_status")) {
            assertContentEquals(source.readColumn(name).doubleValues().toList(), day.file.readColumn(name).doubleValues().toList(), name)
        }
    }

    @Test
    fun theTiersKeepEveryRowAndAddTheFlaggedAndCleanCompanions() {
        val day = build(humidPm25Parquet(), above100)
        val raw = assertNotNull(day.hour(AirMetrics.PM25.id))
        assertEquals(4, raw.n)
        assertEquals(60f, raw.mean)
        val qc = assertNotNull(day.hour(RowChecks.FLAGS_ID))
        assertEquals(1, qc.n)
        assertEquals(4f, qc.mean)
        val clean = assertNotNull(day.hour(RowChecks.cleanId(AirMetrics.PM25.id)))
        assertEquals(3, clean.n)
        assertEquals((9f + 30f + 31f) / 3f, clean.mean, 1e-4f)
        // The corrected PM2.5 has two rows, the flagged one is left out of its clean mean.
        val corrected = assertNotNull(day.hour(RowChecks.cleanId(AirMetrics.PM25_CORRECTED.id)))
        assertEquals(1, corrected.n)
        assertEquals(day.hour(AirMetrics.PM25_CORRECTED.id)!!.min, corrected.mean, 1e-4f)
        // Humidity has no health scale, so it gets no companion.
        assertNotNull(day.hour(AirMetrics.HUMIDITY.id))
        assertNull(day.hour(RowChecks.cleanId(AirMetrics.HUMIDITY.id)))
    }

    @Test
    fun noCheckWritesNoColumnNoKeyAndNoCompanion() {
        val day = build(humidPm25Parquet(), RowChecks.NONE)
        assertTrue(day.file.leaves.none { it.name == RowChecks.FLAGS_COLUMN })
        assertNull(day.file.metadata.keyValueMetadata[RowChecks.CHECKS_KEY])
        assertTrue(day.pass.buckets.flatMap { it.metrics.keys }.none { it.startsWith("~") || it.contains("~clean") })
    }

    @Test
    fun aRowNoFlagIsRaisedOnHasNoCompanions() {
        val day = build(humidPm25Parquet(), RowChecks { object : RowCheck by Above100 { override val id = "test-none-v1"; override fun flags(timeMs: LongArray, values: List<DoubleArray>) = IntArray(timeMs.size) } })
        assertContentEquals(IntArray(5), (day.file.readColumn(RowChecks.FLAGS_COLUMN) as ColumnValues.Int32).values)
        assertNull(day.hour(RowChecks.FLAGS_ID))
        assertNull(day.hour(RowChecks.cleanId(AirMetrics.PM25.id)))
    }

    @Test
    fun theCheckIdIsPartOfTheDaySignature() {
        val record = record(humidPm25Parquet())
        val plain = ArchiveHistoryTiers.sourceLine(record, CoreS3Files, RowChecks.NONE)
        val checked = ArchiveHistoryTiers.sourceLine(record, CoreS3Files, above100)
        assertEquals("$plain:q${Above100.id}", checked)
        val next = object : RowCheck by Above100 { override val id = "test-above-100-v2" }
        assertTrue(checked != ArchiveHistoryTiers.sourceLine(record, CoreS3Files, RowChecks { next }))
    }

    @Test
    fun theInstalledAirCheckOnTheRealBoardArchiveMatchesItsOwnColumns() {
        val bytes = withCoreS3Dict(fixtureBytes("parquet/device/v3-board-2rg-180rows.parquet"))
        val day = build(bytes, RowChecks.INSTALLED)
        val check = assertNotNull(RowChecks.INSTALLED.of(coreS3Template)) as AirQc
        assertEquals(check.id, day.file.metadata.keyValueMetadata[RowChecks.CHECKS_KEY])
        val time = day.file.readColumn(UtcColumns.EVENT_US).longValues()
        val values = check.columns.map { c -> day.file.leaves.firstOrNull { it.name == c }?.let { day.file.readColumn(it).doubleValues().let { v -> DoubleArray(v.size) { i -> v[i] } } } ?: DoubleArray(time.size) { Double.NaN } }
        val stored = (day.file.readColumn(RowChecks.FLAGS_COLUMN) as ColumnValues.Int32).values
        assertContentEquals(check.flags(LongArray(time.size) { time[it] / 1_000 }, values), stored)
        // Every source row is in the day file with its PM2.5 as measured.
        val source = ParquetFile.wrap(bytes).readColumn(AirMetrics.PM25.column).doubleValues().sorted()
        assertEquals(source, day.file.readColumn(AirMetrics.PM25.column).doubleValues().sorted())
    }

    @Test
    fun checkedTiersJoinTheCompanionsByStart() {
        val values = listOf(TierBucket(0, 90, 90, 0, 20f, 5f, 170f), TierBucket(900, 90, 90, 0, 10f, 8f, 12f))
        val flags = listOf(TierBucket(0, 90, 9, 0, 5f, 5f, 5f))
        val clean = listOf(TierBucket(0, 90, 81, 0, 12f, 12f, 12f))
        val joined = CheckedTiers.join(values, flags, clean)
        assertEquals(CheckedBucket(values[0], 9, 5, 12f), joined[0])
        assertEquals(CheckedBucket(values[1]), joined[1])
        // Every row flagged, so no clean mean is left.
        val none = CheckedTiers.join(values.take(1), flags, listOf(TierBucket(0, 90, 0, 0, 0f, 0f, 0f)))
        assertEquals(CheckedBucket(values[0], 90, 5, null), none.single())
        assertEquals(values.map(::CheckedBucket), CheckedTiers.join(values, emptyList(), emptyList()))
    }

    @Test
    fun withoutFlaggedJudgesTheRowsThatPassed() {
        val points = listOf(
            HistoryPoint(0, 900, 20f, 5f, 170f, 1f, flaggedRows = 9, flags = 4, cleanMean = 12f),
            HistoryPoint(900, 1800, 10f, 8f, 14f, 1f),
        )
        val series = HistorySeries(AirMetrics.PM25, HistoryRange.WEEK, 0, 1800, points, 180, 180, 15f, 170f, false, flaggedRows = 9, flags = 4, cleanMean = 11f)
        val simple = series.withoutFlagged()
        assertEquals(171L, simple.validRows)
        assertEquals(11f, simple.mean)
        assertEquals(14f, simple.max)
        assertEquals(0L, simple.flaggedRows)
        assertEquals(HistoryPoint(0, 900, 12f, null, null, 1f), simple.points[0])
        assertEquals(points[1], simple.points[1])
        // The series as measured is unchanged.
        assertEquals(20f, series.points[0].mean)
        val clean = series.copy(flaggedRows = 0, flags = 0, cleanMean = null)
        assertEquals(clean, clean.withoutFlagged())
    }
}
