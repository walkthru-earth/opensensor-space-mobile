package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.archive.ArchiveIndex
import earth.walkthru.aqsync.archive.ArchiveKey
import earth.walkthru.aqsync.archive.ArchiveRecord
import earth.walkthru.aqsync.archive.ArchiveStore
import earth.walkthru.aqsync.archive.PartialMeta
import earth.walkthru.aqsync.archive.PartialWriter
import earth.walkthru.aqsync.data.history.parquet.ByteArrayReader
import earth.walkthru.aqsync.data.history.parquet.ByteReader
import earth.walkthru.aqsync.data.history.parquet.ColumnValues
import earth.walkthru.aqsync.data.history.parquet.ParquetCodec
import earth.walkthru.aqsync.data.history.parquet.ParquetFile
import earth.walkthru.aqsync.data.history.parquet.ParquetLogicalType
import earth.walkthru.aqsync.data.history.parquet.ParquetType
import earth.walkthru.aqsync.data.history.parquet.ParquetWriter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import space.opensensor.model.Aggregate
import space.opensensor.model.AppProfile
import space.opensensor.model.BoardTemplate
import space.opensensor.model.Datastream
import space.opensensor.model.Family
import space.opensensor.model.GateValue
import space.opensensor.model.PlausibleRange
import space.opensensor.model.Role
import space.opensensor.model.TemplateKey
import space.opensensor.model.ValidityGate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #186. History reads its metrics from the board template datastreams, never from a fixed air quality
 * list. A soil board the app has never seen aggregates, gates, settles, rolls up and charts by its own
 * template. A dictionary nothing resolves keeps its rows and has no metrics.
 */
class TemplateDatastreamsTest {
    private val day = 20_000L
    private val startS = day * 86_400 + 10 * 3_600
    private val station = "soil-station"

    private fun ds(name: String, type: String = "float", unit: String? = null, role: Role? = Role.PHYSICAL_MEASUREMENT, aggregate: String? = null, gate: ValidityGate? = null, settle: Double = 0.0, range: PlausibleRange? = null) =
        Datastream(name, type, role, unit, validWhen = gate, settleSeconds = settle, aggregate = aggregate?.let(::Aggregate), plausibleRange = range)

    private val soil = BoardTemplate(
        key = TemplateKey("ab".repeat(32)),
        id = "soil-probe-v1",
        schema = "soil-probe-v1",
        title = null,
        keywords = emptyList(),
        instruments = emptyList(),
        defaultSampleIntervalS = 300.0,
        protoMin = 1,
        primaryDatetime = UtcColumns.EVENT_NS,
        datastreams = listOf(
            ds(UtcColumns.EVENT_NS, "int64", role = Role("auxiliaryInformation")),
            ds(ParquetIndexer.MONO, "int64", role = Role("auxiliaryInformation")),
            ds("probe_status", "int32", role = Role("qualityInformation")),
            ds("soil_moisture_percent", unit = "%", gate = ValidityGate.Equals("probe_status", GateValue.Number(1.0)), settle = 60.0, range = PlausibleRange(0.0, 100.0)),
            ds("soil_temperature_c", unit = "Cel"),
            ds("rain_tips", "int32", unit = "{tips}", aggregate = "sum"),
            ds("wind_class", "int32", aggregate = "mode"),
            ds("gust_m_s", unit = "m/s", aggregate = "maximum"),
            ds("frost_c", unit = "Cel", aggregate = "minimum"),
            ds("tip_counter", "int64", aggregate = "point"),
            ds("leaf_wetness", aggregate = "circular_mean"),
        ),
        liveKeys = emptyMap(),
        dictionaryHref = null,
    )
    private val soilFiles = FileTemplates { dict, _ -> soil.takeIf { it.dict == dict } }

    /** An `opensensor` footer naming [dict] and [intervalMs], with no template digest. */
    private fun footer(dict: String, intervalMs: Long? = null): Map<String, String> =
        mapOf(OpenSensorFooter.KEY to opensensorFooterJson(dict, desc = null, intervalMs = intervalMs, board = "soil-probe", schema = "soil-v1"))
    private val metrics = Metrics.of(soil)
    private fun metric(id: String) = metrics.single { it.id == id }

    /** Eight rows 30 s apart from 10:00Z of [day], footer cadence 30 s against a 300 s template default. */
    private fun soilFile(footer: Map<String, String> = footer(soil.dict, 30_000)): ByteArray {
        val n = 8
        val sink = MemorySink()
        val w = ParquetWriter(
            sink,
            listOf(
                ParquetWriter.Column(UtcColumns.EVENT_NS, ParquetType.INT64, timestampUtc = ParquetLogicalType.TimeUnit.NANOS),
                ParquetWriter.Column(ParquetIndexer.MONO, ParquetType.INT64),
                ParquetWriter.Column("probe_status", ParquetType.INT32),
                ParquetWriter.Column("soil_moisture_percent", ParquetType.FLOAT),
                ParquetWriter.Column("soil_temperature_c", ParquetType.FLOAT),
                ParquetWriter.Column("rain_tips", ParquetType.INT32),
                ParquetWriter.Column("wind_class", ParquetType.INT32),
                ParquetWriter.Column("gust_m_s", ParquetType.FLOAT),
                ParquetWriter.Column("frost_c", ParquetType.FLOAT),
                ParquetWriter.Column("tip_counter", ParquetType.INT64),
                ParquetWriter.Column("leaf_wetness", ParquetType.FLOAT),
            ),
            ParquetCodec.UNCOMPRESSED,
            keyValueMetadata = footer,
        )
        w.begin()
        w.rowGroup(
            listOf(
                ColumnValues.Int64(LongArray(n) { (startS + it * 30L) * 1_000_000_000L }, null),
                // Power on 10 s before the first row, so the first two rows fall in the 60 s settle window.
                ColumnValues.Int64(LongArray(n) { (10L + it * 30L) * 1_000_000L }, null),
                ColumnValues.Int32(intArrayOf(1, 1, 0, 1, 1, 1, 1, 1), null),
                ColumnValues.Float32(floatArrayOf(20f, 22f, 99f, 24f, 26f, 28f, 30f, 32f), null),
                ColumnValues.Float32(floatArrayOf(10f, 11f, 12f, 13f, 14f, 15f, 16f, 17f), null),
                ColumnValues.Int32(intArrayOf(0, 1, 0, 2, 0, 0, 3, 1), null),
                ColumnValues.Int32(intArrayOf(3, 3, 5, 5, 5, 1, 2, 2), null),
                ColumnValues.Float32(floatArrayOf(1f, 4f, 2f, 9f, 3f, 5f, 1f, 2f), null),
                ColumnValues.Float32(floatArrayOf(2f, 1f, -3f, 0f, 1f, 2f, 3f, 4f), null),
                ColumnValues.Int64(LongArray(n) { 100L + it }, null),
                ColumnValues.Float32(floatArrayOf(5f, 6f, 7f, 8f, 9f, 10f, 11f, 12f), null),
            ),
        )
        w.finish()
        return sink.toByteArray()
    }

    private fun hour(files: FileTemplates, bytes: ByteArray = soilFile(), list: List<MetricSpec> = metrics): Bucket =
        Aggregator(station, setOf(Resolution.HOUR), list, files).apply { add(ParquetFile.wrap(bytes)) }.buckets().single()

    @Test
    fun theMetricListIsTheTemplatesMeasuredColumnsWithTheProfilePrimaryFirst() {
        assertEquals(
            listOf("soil_moisture_percent", "soil_temperature_c", "rain_tips", "wind_class", "gust_m_s", "frost_c", "tip_counter", "leaf_wetness"),
            metrics.map { it.id },
        )
        val profile = AppProfile(soil.dict, Family.GENERIC, primary = listOf("frost_c", "rain_tips"))
        assertEquals(listOf("frost_c", "rain_tips", "soil_moisture_percent"), Metrics.of(soil, profile).take(3).map { it.id })
        assertEquals(listOf(true, true, false), Metrics.of(soil, profile).take(3).map { it.primary })
    }

    @Test
    fun theDefaultMetricIsTheFirstPrimaryColumnOfTheProfile() {
        assertEquals("soil_moisture_percent", Metrics.defaultOf(metrics)?.id)
        assertEquals("soil_moisture_percent", Metrics.defaultOf(metrics.reversed())?.id)
        val profile = AppProfile(soil.dict, Family.GENERIC, primary = listOf("soil_temperature_c"))
        assertEquals("soil_temperature_c", Metrics.defaultOf(Metrics.of(soil, profile))?.id)
        // A bundled air board opens on PM2.5, its corrected view where the board stores RH (#92).
        assertEquals(Metrics.PM25_CORRECTED.id, Metrics.default.id)
        assertEquals(Metrics.PM25_CORRECTED.id, Metrics.defaultOf(Metrics.of(coreS3Template))?.id)
        assertEquals(Metrics.PM25.id, Metrics.defaultOf(Metrics.of(coreS3Template).filter { it.humidityColumn == null })?.id)
    }

    @Test
    fun aSoilOnlyTemplateGivesValidRowsGatedSettledAndRolledUpByItsTemplate() {
        val b = hour(soilFiles)
        assertEquals(8, b.rows)
        val moisture = b.agg(metric("soil_moisture_percent"))!!
        // The probe_status gate drops the 99 % row. Two of the seven valid rows were within 60 s of power on.
        assertEquals(7, moisture.n)
        assertEquals(26f, moisture.mean, 1e-4f)
        assertEquals(2, moisture.settling)
        // No aggregate in the template is a mean.
        assertEquals(13.5f, b.agg(metric("soil_temperature_c"))!!.mean, 1e-4f)
        assertEquals(0, b.agg(metric("soil_temperature_c"))!!.settling)
        assertEquals(7f, b.agg(metric("rain_tips"))!!.mean)
        assertEquals(5f, b.agg(metric("wind_class"))!!.mean)
        assertEquals(9f, b.agg(metric("gust_m_s"))!!.mean)
        assertEquals(-3f, b.agg(metric("frost_c"))!!.mean)
        // A counter stays a point, and an aggregate this build does not know rolls up as a point too.
        assertEquals(107f, b.agg(metric("tip_counter"))!!.mean)
        assertEquals(Aggregate.POINT, metric("leaf_wetness").aggregate)
        assertEquals(12f, b.agg(metric("leaf_wetness"))!!.mean)
    }

    @Test
    fun theFooterCadenceWinsOverTheTemplateDefault() {
        val b = hour(soilFiles)
        assertEquals(30_000L, b.sampleIntervalMs)
        assertEquals(120L, b.expectedRows)
        assertEquals(7f / 120f, b.coverage(metric("soil_moisture_percent")), 1e-6f)
        // Without a footer interval the template default holds, and with neither nothing is assumed.
        assertEquals(300_000L, soilFiles.of(footer(soil.dict)).sampleIntervalMs)
        assertEquals(300_000L, soilFiles.of(footer(soil.dict, 0)).sampleIntervalMs)
        assertEquals(0L, soilFiles.of(emptyMap()).sampleIntervalMs)
        val unknown = hour(soilFiles, soilFile(footer("cd".repeat(32))))
        assertEquals(0L, unknown.expectedRows)
        assertEquals(0f, unknown.coverage(metric("soil_moisture_percent")))
    }

    @Test
    fun aDictionaryNothingResolvesKeepsItsRowsAndHasNoMetrics() {
        val bundled = hour(FileTemplates.BUNDLED)
        assertEquals(8, bundled.rows)
        assertEquals(emptyMap(), bundled.metrics)
        assertEquals(emptyMap(), hour(FileTemplates.BUNDLED, list = Metrics.all).metrics)
        // The soil file indexes with its row count and no valid rows to show.
        val summary = ParquetIndexer.index(ParquetFile.wrap(soilFile()), FileTemplates.BUNDLED)
        assertEquals(8L, summary.rows)
        assertEquals(null, summary.validRows)
        assertEquals(7L, ParquetIndexer.index(ParquetFile.wrap(soilFile()), soilFiles).validRows)
    }

    @Test
    fun aFileWhoseTemplateStoresAMetricInAnotherUnitGivesItNoValues() {
        val fahrenheit = soil.copy(
            key = TemplateKey("ef".repeat(32)),
            datastreams = soil.datastreams.map {
                if (it.name == "soil_temperature_c") {
                    it.copy(unit = "[degF]")
                } else if (it.name == "rain_tips") {
                    it.copy(aggregate = Aggregate.MEAN)
                } else {
                    it
                }
            },
        )
        val files = FileTemplates { dict, _ -> listOf(soil, fahrenheit).firstOrNull { it.dict == dict } }
        val b = hour(files, soilFile(footer(fahrenheit.dict)))
        assertEquals(null, b.agg(metric("soil_temperature_c")))
        // A column rolled up another way is not mixed in either.
        assertEquals(null, b.agg(metric("rain_tips")))
        assertEquals(7, b.agg(metric("soil_moisture_percent"))!!.n)
    }

    @Test
    fun aFileTakesTheGateAndSettleTimeOfItsOwnTemplate() {
        val loose = soil.copy(
            key = TemplateKey("12".repeat(32)),
            datastreams = soil.datastreams.map { if (it.name == "soil_moisture_percent") it.copy(validWhen = null, settleSeconds = 0.0) else it },
        )
        val b = hour(FileTemplates { _, _ -> loose }, soilFile(footer(loose.dict)))
        val moisture = b.agg(metric("soil_moisture_percent"))!!
        assertEquals(8, moisture.n)
        assertEquals(0, moisture.settling)
    }

    @Test
    fun aSoilDayChartsFromItsRowsOnItsPlausibleRange() = runTest {
        val bytes = soilFile()
        val store = Store()
        val index = Index()
        val r = ArchiveRecord(station, "station=$station/soil.parquet", bytes.size.toLong(), 1, "s", 1, "fake")
        store.files[r.key] = bytes
        index.put(r)
        ArchiveIndexRepair(store, index, nowEpochMs = { 42L }, templates = soilFiles).backfillAll()
        assertTrue(index.rows.value.single().isIndexed)
        val reader = HistorySeriesReader(store, index, ArchiveHistoryTiers(store, index, templates = soilFiles), templates = soilFiles)
        val window = HistoryWindow(day * 86_400, (day + 1) * 86_400, TimeZone.UTC)
        val moisture = metric("soil_moisture_percent")
        val series = reader.observe(station, moisture, HistoryRange.DAY, window).first()
        assertEquals(7L, series.validRows)
        assertEquals(86_400L * 1000 / 30_000, series.expectedRows)
        assertEquals(26f, series.mean!!, 1e-4f)
        assertTrue(series.points.isNotEmpty())
        val counted = reader.observe(station, metric("rain_tips"), HistoryRange.DAY, window).first()
        assertEquals(7f, counted.mean)
        // The axis holds the data inside the template's plausible range, and with no data it is that range.
        val axis = ChartAxes.of(series.points.map { HistoryColumn(it.startS, it.endS, it.mean, it.max, it.coverage, 0f) }, moisture)
        assertTrue(axis.min <= 20f && axis.max >= 32f && axis.max <= 100f)
        assertEquals(ChartAxis(0f, 100f, axis(0f, 100f).step), ChartAxes.of(emptyList(), moisture))
    }

    private fun axis(min: Float, max: Float) = ChartAxis(min, max, Columns.niceStep(max - min))

    @Test
    fun rollupsFollowTheAggregate() {
        fun rolled(aggregate: String, vararg values: Double): Float? = Rollup(Aggregate(aggregate)).apply { values.forEachIndexed { i, v -> add(v, i.toLong()) } }.value
        assertEquals(2f, rolled("mean", 1.0, 2.0, 3.0))
        assertEquals(6f, rolled("sum", 1.0, 2.0, 3.0))
        assertEquals(1f, rolled("minimum", 2.0, 1.0, 3.0))
        assertEquals(3f, rolled("maximum", 2.0, 3.0, 1.0))
        assertEquals(2f, rolled("mode", 2.0, 3.0, 2.0, 1.0))
        assertEquals(1f, rolled("point", 2.0, 3.0, 1.0))
        assertEquals(1f, rolled("circular_mean", 2.0, 3.0, 1.0))
        assertEquals(null, rolled("mean"))
        val parts = listOf(Partial(2f, 1, 10), Partial(4f, 3, 5), Partial(9f, 0, 20))
        assertEquals(3.5f, combine(Aggregate.MEAN, parts))
        assertEquals(6f, combine(Aggregate.SUM, parts))
        assertEquals(2f, combine(Aggregate.MINIMUM, parts))
        assertEquals(4f, combine(Aggregate.MAXIMUM, parts))
        assertEquals(4f, combine(Aggregate.MODE, parts))
        assertEquals(2f, combine(Aggregate.POINT, parts))
        assertEquals(2f, combine(Aggregate("circular_mean"), parts))
        assertEquals(null, combine(Aggregate.MEAN, listOf(Partial(1f, 0, 0))))
    }

    @Test
    fun aMissingAggregateIsAMeanAndAnUnknownOneAPoint() {
        assertEquals(Aggregate.MEAN, Metrics.rollupOf(ds("a")))
        assertEquals(Aggregate.POINT, Metrics.rollupOf(ds("a", aggregate = "circular_mean")))
        assertEquals(Aggregate.SUM, Metrics.rollupOf(ds("a", aggregate = "sum")))
    }

    @Test
    fun gatesReadNumbersFlagsAndText() {
        val numbers = ColumnValues.Int32(intArrayOf(0, 1, 4, 0), booleanArrayOf(true, true, true, false))
        val flag = GateColumn(ValidityGate.Equals("c", GateValue.Flag(true)), numbers)
        assertEquals(listOf(false, true, true, false), (0..3).map(flag::passes))
        val off = GateColumn(ValidityGate.Equals("c", GateValue.Flag(false)), numbers)
        assertEquals(listOf(true, false, false, false), (0..3).map(off::passes))
        val number = GateColumn(ValidityGate.Equals("c", GateValue.Number(4.0)), numbers)
        assertEquals(listOf(false, false, true, false), (0..3).map(number::passes))
        val finite = GateColumn(ValidityGate.Finite("c"), ColumnValues.Float32(floatArrayOf(1f, Float.NaN, Float.POSITIVE_INFINITY), null))
        assertEquals(listOf(true, false, false), (0..2).map(finite::passes))
        val words = "okbadok".encodeToByteArray()
        val text = ColumnValues.Binary(intArrayOf(0, 2, 5, 7, 7), words, booleanArrayOf(true, true, true, false))
        val ok = GateColumn(ValidityGate.Equals("c", GateValue.Text("ok")), text)
        assertEquals(listOf(true, false, true, false), (0..3).map(ok::passes))
        assertEquals(false, GateColumn(ValidityGate.Equals("c", GateValue.Text("ok")), numbers).passes(1))
        assertEquals(false, GateColumn(ValidityGate.Finite("c"), null).passes(0))
    }

    private class Store : ArchiveStore {
        val files = HashMap<ArchiveKey, ByteArray>()
        override suspend fun partialLengths(station: String): Map<String, Long> = emptyMap()
        override suspend fun openPartial(key: ArchiveKey, meta: PartialMeta): PartialWriter = error("read only")
        override suspend fun discardPartial(key: ArchiveKey) = Unit
        override suspend fun exists(key: ArchiveKey): Boolean = key in files
        override fun reader(key: ArchiveKey, partial: Boolean): ByteReader = ByteArrayReader(files[key] ?: throw NoSuchElementException(key.name))
        override suspend fun commit(key: ArchiveKey) = Unit
        override suspend fun listFinal(station: String?): List<ArchiveKey> = files.keys.filter { station == null || it.station == station }
    }

    private class Index : ArchiveIndex {
        val rows = MutableStateFlow<List<ArchiveRecord>>(emptyList())
        override fun observeAll(): Flow<List<ArchiveRecord>> = rows
        override fun observeStation(station: String): Flow<List<ArchiveRecord>> = rows.map { list -> list.filter { it.station == station } }
        override suspend fun get(key: ArchiveKey): ArchiveRecord? = rows.value.firstOrNull { it.key == key }
        override suspend fun put(record: ArchiveRecord) {
            rows.value = rows.value.filterNot { it.key == record.key } + record
        }
        override suspend fun remove(key: ArchiveKey) {
            rows.value = rows.value.filterNot { it.key == key }
        }
        override suspend fun unindexed(limit: Int): List<ArchiveRecord> = rows.value.filter { !it.isIndexed }.take(limit)
        override suspend fun timeline(station: String, fromNs: Long, toNs: Long): List<ArchiveRecord> =
            rows.value.filter { it.station == station && it.onTimeline(fromNs, toNs) }.sortedWith(ArchiveRecord.TIMELINE_ORDER)
        override suspend fun indexedForStation(station: String): List<ArchiveRecord> = rows.value.filter { it.station == station && it.isIndexed }
        override suspend fun stations(): List<String> = rows.value.map { it.station }.distinct()
    }
}
