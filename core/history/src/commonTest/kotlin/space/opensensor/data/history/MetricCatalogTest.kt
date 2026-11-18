package space.opensensor.data.history

import space.opensensor.data.history.parquet.ColumnValues
import space.opensensor.data.history.parquet.ParquetCodec
import space.opensensor.data.history.parquet.ParquetFile
import space.opensensor.data.history.parquet.ParquetLogicalType
import space.opensensor.data.history.parquet.ParquetType
import space.opensensor.data.history.parquet.ParquetWriter
import space.opensensor.family.air.AirFamily
import space.opensensor.model.BoardTemplate
import space.opensensor.model.BundledCatalog
import space.opensensor.model.Datastream
import space.opensensor.model.Role
import space.opensensor.model.TemplateKey
import space.opensensor.model.Thing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The metrics of one Thing and the union that grows as boards serve templates the app never bundled. */
class MetricCatalogTest {
    init {
        AirFamily.install()
    }

    /** A board no bundled template describes, served in the test. Its primary is its second column. */
    private val probe = BoardTemplate(
        key = TemplateKey("ab".repeat(32), desc = "cd".repeat(32)),
        id = "acme-soil-v1",
        schema = "acme-soil-v1",
        title = null,
        keywords = emptyList(),
        instruments = emptyList(),
        defaultSampleIntervalS = 10.0,
        protoMin = 1,
        primaryDatetime = UtcColumns.EVENT_NS,
        datastreams = listOf(
            Datastream(UtcColumns.EVENT_NS, "int64", Role("auxiliaryInformation")),
            Datastream("soil_temperature_c", "float", Role.PHYSICAL_MEASUREMENT, unit = "Cel"),
            Datastream("soil_moisture_percent", "float", Role.PHYSICAL_MEASUREMENT, unit = "%"),
        ),
        liveKeys = emptyMap(),
        dictionaryHref = null,
        primary = "soil_moisture_percent",
    )

    @Test
    fun aServedTemplateJoinsTheUnionOnce() {
        val served = ServedTemplates(catalog = MetricCatalog())
        val before = served.catalog.union.value
        assertEquals(Metrics.catalogOf(BundledCatalog.templates).map { it.id }, before.map { it.id })
        assertTrue(before.none { it.id == "soil_moisture_percent" })

        served.add(probe)
        val grown = served.catalog.union.value
        assertTrue(grown.map { it.id }.containsAll(before.map { it.id } + "soil_moisture_percent"))
        assertEquals("soil_moisture_percent", served.catalog.byId("soil_moisture_percent")?.column)

        served.add(probe)
        assertSame(grown, served.catalog.union.value)
    }

    @Test
    fun theUnionNeverShrinksWhenAServedTemplateIsDropped() {
        val served = ServedTemplates(catalog = MetricCatalog())
        served.add(probe)
        served.remove(probe.key.desc!!)
        assertTrue(served.catalog.union.value.any { it.id == "soil_moisture_percent" })
    }

    @Test
    fun oneThingHasItsOwnMetricsAndOpensOnItsPrimary() {
        val metrics = MetricCatalog.of(Thing.join("acme-1", probe))
        assertEquals(listOf("soil_moisture_percent", "soil_temperature_c"), metrics.map { it.id })
        assertEquals("soil_moisture_percent", Metrics.defaultOf(metrics)?.id)
    }

    @Test
    fun aTierOfAServedBoardAggregatesWithTheUnion() {
        val served = ServedTemplates(catalog = MetricCatalog())
        served.add(probe)
        val agg = Aggregator("acme-1", templates = FileTemplates.over(served))
        agg.add(ParquetFile.wrap(probeParquet()))
        val day = agg.buckets().single { it.resolution == Resolution.DAY }
        val moisture = checkNotNull(served.catalog.byId("soil_moisture_percent"))
        assertEquals(3, day.agg(moisture)?.n)
        assertEquals(31f, day.agg(moisture)!!.mean, 1e-4f)
    }

    @Test
    fun tieredBindsTheUnionMetricToOneTemplate() {
        val catalog = MetricCatalog()
        catalog.adopt(probe)
        val moisture = checkNotNull(catalog.byId("soil_moisture_percent"))
        assertEquals(moisture, catalog.tiered(moisture, probe))
        assertEquals(null, catalog.tiered(moisture, BundledCatalog.templates.first { it.datastream("soil_moisture_percent") == null }))
    }

    /** [probe] with a board temperature, a battery and three auxiliary columns that show no unit. */
    private val probeWithBoard = probe.copy(
        key = TemplateKey("ef".repeat(32), desc = "fe".repeat(32)),
        datastreams = probe.datastreams + listOf(
            Datastream("board_temperature_c", "float", Role.AUXILIARY_INFORMATION, unit = "Cel"),
            Datastream("battery_mv", "int32", Role.AUXILIARY_INFORMATION, unit = "mV"),
            Datastream("charging_status", "int32", Role.AUXILIARY_INFORMATION, unit = "{code}"),
            Datastream("rows_dropped", "int64", Role.AUXILIARY_INFORMATION, unit = "{count}"),
            Datastream("firmware_tag", "string", Role.AUXILIARY_INFORMATION, unit = "mV"),
            Datastream("clock_status", "int32", Role.QUALITY_INFORMATION, unit = "ms"),
        ),
    )

    @Test
    fun everyNumericAuxiliaryColumnWithAUnitChartsAfterTheMeasurements() {
        val metrics = Metrics.of(probeWithBoard)
        assertEquals(listOf("soil_moisture_percent", "soil_temperature_c", "board_temperature_c", "battery_mv"), metrics.map { it.id })
        assertEquals(listOf(false, false, true, true), metrics.map { it.auxiliary })
    }

    @Test
    fun theCoreS3BoardTemperatureAndBatteryAreChartChoices() {
        val coreS3 = BundledCatalog.templates.single { it.datastream("imu_temperature_c") != null }
        val metrics = Metrics.of(coreS3)
        val ids = metrics.map { it.id }
        assertTrue(ids.containsAll(listOf("imu_temperature_c", "battery_percent", "battery_mv")))
        assertTrue(ids.none { it in setOf("charging_status", "external_5v_enabled", "rows_dropped", "mag_x_raw", "pms_age_ms", "event_time_utc_us") })
        assertTrue(metrics.dropWhile { !it.auxiliary }.all { it.auxiliary })
        assertTrue(MetricCatalog.shared.union.value.dropWhile { !it.auxiliary }.all { it.auxiliary })
    }

    @Test
    fun theUnionKeepsTheOrderOfItsTemplatesNotOfTheirDicts() {
        val first = Metrics.catalogOf(listOf(probeWithBoard) + BundledCatalog.templates).map { it.id }
        val last = Metrics.catalogOf(BundledCatalog.templates + probeWithBoard).map { it.id }
        assertEquals("soil_moisture_percent", first.first())
        assertTrue(last.indexOf("soil_moisture_percent") > last.indexOf("ambient_temperature_c"))
    }

    @Test
    fun aPickerListsTheThingsOwnMetricsFirstThenTheRestAuxiliaryLast() {
        val union = Metrics.catalogOf(BundledCatalog.templates + probeWithBoard)
        val picked = Metrics.pickerOrder(union, probeWithBoard).map { it.id }
        assertEquals(listOf("soil_moisture_percent", "soil_temperature_c"), picked.take(2))
        val firstAux = picked.indexOfFirst { id -> union.first { it.id == id }.auxiliary }
        assertEquals(listOf("board_temperature_c", "battery_mv"), picked.drop(firstAux).take(2))
        assertTrue(picked.take(firstAux).none { id -> union.first { it.id == id }.auxiliary })
        assertEquals(union, Metrics.pickerOrder(union, null))
    }

    private fun probeParquet(): ByteArray {
        val out = MemorySink()
        val w = ParquetWriter(
            out,
            listOf(
                ParquetWriter.Column(UtcColumns.EVENT_NS, ParquetType.INT64, timestampUtc = ParquetLogicalType.TimeUnit.NANOS),
                ParquetWriter.Column("soil_temperature_c", ParquetType.FLOAT),
                ParquetWriter.Column("soil_moisture_percent", ParquetType.FLOAT),
            ),
            ParquetCodec.UNCOMPRESSED,
            keyValueMetadata = opensensorFooter(probe, thing = "acme-1"),
        )
        w.begin()
        w.rowGroup(
            listOf(
                ColumnValues.Int64(LongArray(3) { (HUMID_HOUR_S + it * 10L) * 1_000_000_000L }, null),
                ColumnValues.Float32(floatArrayOf(14f, 14.5f, 15f), null),
                ColumnValues.Float32(floatArrayOf(30f, 31f, 32f), null),
            ),
        )
        w.finish()
        return out.toByteArray()
    }
}
