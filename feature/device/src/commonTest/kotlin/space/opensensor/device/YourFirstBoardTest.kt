package space.opensensor.device

import space.opensensor.data.history.FileSummary
import space.opensensor.data.history.FileTemplates
import space.opensensor.data.history.Metrics
import space.opensensor.data.history.OpenSensorFooter
import space.opensensor.data.history.ServedTemplates
import space.opensensor.data.history.UtcColumns
import space.opensensor.data.history.opensensorFooterJson
import space.opensensor.data.history.parquet.ColumnValues
import space.opensensor.data.history.parquet.ParquetCodec
import space.opensensor.data.history.parquet.ParquetFile
import space.opensensor.data.history.parquet.ParquetLogicalType
import space.opensensor.data.history.parquet.ParquetSink
import space.opensensor.data.history.parquet.ParquetType
import space.opensensor.data.history.parquet.ParquetWriter
import space.opensensor.data.protocol.BoardSettings
import space.opensensor.data.protocol.ConfigPatch
import space.opensensor.data.protocol.ConfigValidationException
import space.opensensor.data.protocol.Confirm
import space.opensensor.data.protocol.DeviceConfig
import space.opensensor.data.protocol.FieldType
import space.opensensor.data.protocol.ParquetVerify
import space.opensensor.data.protocol.SetupAck
import space.opensensor.data.protocol.sha256Hex
import space.opensensor.data.sync.PlanStepKind
import space.opensensor.data.sync.SetupPlan
import space.opensensor.model.ACME_SOIL_PROBE_TEMPLATE
import space.opensensor.model.BoardTemplate
import space.opensensor.model.StepId
import space.opensensor.settings.DetailLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The end to end path of docs/your-first-board.md for a board no app code names, the Acme soil probe.
 * From the exact template bytes the test plans setup, builds the Device settings cards, resolves the
 * metrics and summarises a Parquet file whose footer names the template by its SHA-256. No family is
 * installed and nothing in the app names a soil column, so every value below comes from the template.
 */
class YourFirstBoardTest {
    private val bytes = ACME_SOIL_PROBE_TEMPLATE.encodeToByteArray()

    /** The template digest, the SHA-256 of its exact bytes, as a footer `template.sha256` names it. */
    private val desc = ParquetVerify.sha256Hex(bytes)
    private val acme = BoardTemplate.parse(bytes.decodeToString(), desc = desc)

    /** Page 1 of a fixed Thing with a cell, so the location step is satisfied. */
    private val page1 = DeviceConfig.parse("""{"location":{"cell":"8828308281fffff","resolution":8},"thing":{"mobility":"fixed"}}""")

    @Test
    fun setupPlansTheBoardStepFromTheTemplateWithItsTitleAndKeys() {
        val plan = SetupPlan.of(acme.setup, page1)
        // The catalog steps keep the template order. The unknown required step needs a newer app.
        assertEquals(listOf("name", "location", "wifi", "probe", "acme-mystery"), plan.steps.map { it.id.id })
        assertEquals(SetupAck.version(listOf("name", "location", "wifi", "probe", "acme-mystery")), plan.version)
        val probe = assertNotNull(plan.step(StepId("probe")))
        assertEquals(PlanStepKind.GENERIC, probe.kind)
        assertTrue(probe.required)
        assertEquals(3, probe.ackIndex)
        assertEquals("Soil probe", probe.label?.get("en"))
        assertEquals("مجس التربة", probe.label?.get("ar"))
        // Only declared page 7 keys are fields, the undeclared one is dropped.
        assertEquals(listOf("probe.depth_cm", "probe.mode"), probe.fields.map { it.field.key })
        assertEquals(FieldType.IntBetween(5, 60), probe.fields[0].field.type)
        assertEquals("عمق المجس", probe.fields[0].label?.get("ar"))
        assertEquals(FieldType.Choice(listOf("fast", "slow")), probe.fields[1].field.type)
        assertEquals(PlanStepKind.UNSUPPORTED, plan.step(StepId("acme-mystery"))?.kind)
        // Finishing the probe step sets its bit and keeps setup pending on the unsupported step.
        assertEquals(1 shl 3, plan.ackWith(StepId("probe")))
        assertTrue(plan.unsupported)
    }

    @Test
    fun deviceSettingsShowTheBoardSectionAsAFormOfItsKeys() {
        val cards = DeviceSection.of(acme.setup, DetailLevel.SIMPLE, pairingNeedsAttention = false, sensorIdentity = false)
        assertEquals(DeviceSection.Location, cards.first())
        val probe = cards.filterIsInstance<DeviceSection.Board>().single()
        assertEquals("Probe", probe.title("en"))
        assertEquals("المجس", probe.title("ar"))
        assertEquals(listOf("probe.depth_cm", "probe.mode", "probe.calibrate"), probe.fields.map { it.field.key })
        // A board action always confirms, whatever the template says.
        val calibrate = probe.fields.last().field
        assertEquals(FieldType.Action, calibrate.type)
        assertEquals(Confirm.Always, calibrate.confirm)
        // The form reads its values from GET_CONFIG page 7 and writes one SET_CONFIG key.
        val values = BoardSettings.parse("""{"probe":{"depth_cm":20,"mode":"slow"},"wifi":{"psk":"x"}}""")
        assertEquals(mapOf("probe.depth_cm" to "20", "probe.mode" to "slow"), values.values)
        val depth = probe.fields.first().field
        assertEquals("probe.depth_cm", ConfigPatch.Builder().field(depth, "30").build().describe())
        assertFailsWith<ConfigValidationException> { ConfigPatch.Builder().field(depth, "90").build() }
    }

    @Test
    fun metricsTakeTheirLabelsDecimalsAndPrimaryFromTheTemplate() {
        val metrics = Metrics.of(acme, providers = emptyList())
        assertEquals(listOf("soil_moisture_pct", "soil_temperature_c"), metrics.map { it.id })
        val moisture = metrics.first()
        assertTrue(moisture.primary)
        assertEquals(moisture, Metrics.defaultOf(metrics))
        assertEquals("Soil moisture", moisture.label("en"))
        assertEquals("رطوبة التربة", moisture.label("ar"))
        assertEquals(1, moisture.decimals)
        assertEquals("%", moisture.unit)
        assertNotNull(moisture.healthScale)
        val temperature = metrics.last()
        assertEquals("°C", temperature.unit)
        assertEquals(2, temperature.decimals)
        assertNull(temperature.healthScale)
    }

    @Test
    fun aFileSummaryResolvesTheTemplateByItsDigestAndGatesItsRows() {
        val served = ServedTemplates().apply { add(acme) }
        val templates = FileTemplates.over(served)
        val metrics = Metrics.of(acme, providers = emptyList())
        val summary = FileSummary.of("acme.parquet", ParquetFile.wrap(soilFile(desc)), metrics = metrics, templates = templates)
        assertEquals(6, summary.rows)
        assertEquals(6, summary.timedRows)
        // Only rows with probe_status 1 count, the dry probe and the missing reading do not.
        val moisture = assertNotNull(summary.agg(metrics.first()))
        assertEquals(4, moisture.n)
        assertEquals(23f, moisture.mean, 1e-4f)
        // A footer naming other template bytes resolves nothing, so the file keeps its rows and no values.
        val other = FileSummary.of("other.parquet", ParquetFile.wrap(soilFile("ab".repeat(32))), metrics = metrics, templates = templates)
        assertEquals(6, other.rows)
        assertTrue(other.metrics.isEmpty())
    }

    /** Six rows of the Acme probe, one a minute, under a footer naming [templateDesc]. */
    private fun soilFile(templateDesc: String): ByteArray {
        val status = intArrayOf(1, 1, 2, 1, 0, 1)
        val moisture = floatArrayOf(20f, 22f, 99f, 24f, 50f, 26f)
        val n = status.size
        val startUs = 1_763_000_000L * 1_000_000L
        val footer = opensensorFooterJson(acme.dict, templateDesc, intervalMs = 60_000, board = "acme-soil-probe", schema = "acme-soil-probe-v1")
        val sink = BytesSink()
        val writer = ParquetWriter(
            sink,
            listOf(
                ParquetWriter.Column(UtcColumns.EVENT_US, ParquetType.INT64, timestampUtc = ParquetLogicalType.TimeUnit.MICROS),
                ParquetWriter.Column("probe_status", ParquetType.INT32),
                ParquetWriter.Column("soil_moisture_pct", ParquetType.FLOAT),
                ParquetWriter.Column("soil_temperature_c", ParquetType.FLOAT),
                ParquetWriter.Column("battery_mv", ParquetType.INT32),
            ),
            ParquetCodec.UNCOMPRESSED,
            keyValueMetadata = mapOf(OpenSensorFooter.KEY to footer),
        )
        writer.begin()
        writer.rowGroup(
            listOf(
                ColumnValues.Int64(LongArray(n) { startUs + 60_000_000L * it }, null),
                ColumnValues.Int32(status, null),
                ColumnValues.Float32(moisture, null),
                ColumnValues.Float32(FloatArray(n) { 18f + it }, null),
                ColumnValues.Int32(IntArray(n) { 3_700 - it }, null),
            ),
        )
        writer.finish()
        return sink.bytes()
    }

    private class BytesSink : ParquetSink {
        private val chunks = ArrayList<ByteArray>()

        override fun write(bytes: ByteArray) {
            chunks += bytes.copyOf()
        }

        fun bytes(): ByteArray {
            val out = ByteArray(chunks.sumOf { it.size })
            var at = 0
            for (chunk in chunks) {
                chunk.copyInto(out, at)
                at += chunk.size
            }
            return out
        }
    }
}
