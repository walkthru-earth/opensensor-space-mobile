package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.data.history.parquet.ColumnValues
import earth.walkthru.aqsync.data.history.parquet.ParquetCodec
import earth.walkthru.aqsync.data.history.parquet.ParquetFile
import earth.walkthru.aqsync.data.history.parquet.ParquetLogicalType
import earth.walkthru.aqsync.data.history.parquet.ParquetSink
import earth.walkthru.aqsync.data.history.parquet.ParquetType
import earth.walkthru.aqsync.data.history.parquet.ParquetWriter
import kotlin.math.abs

// Shared by the history tests that read real device files, on the JVM (`jvmTest`) and on the iOS
// simulator (`iosSimulatorArm64Test`). The fixtures are the ones :core:parquet publishes, never a copy.
// The asserts keep JUnit's argument order (message first) so the tests read as they did on the JVM.
// Tests that import `kotlin.test` asserts by name keep those, an explicit import wins over this file.

/** Bytes of a fixture under `core/parquet/src/test/resources`, as stored. */
expect fun rawFixtureBytes(path: String): ByteArray

/**
 * Bytes of a fixture under `core/parquet/src/test/resources`, for example `parquet/expected.json`. A
 * device `.parquet` fixture predates the `opensensor` footer, so its old unprefixed keys are moved
 * into one by [migrateLegacyFooter] first. The app reads no unprefixed key (decision 25).
 */
fun fixtureBytes(path: String): ByteArray = rawFixtureBytes(path).let { if (path.endsWith(".parquet")) migrateLegacyFooter(it) else it }

/** A [ParquetSink] that collects everything in memory. */
class MemorySink : ParquetSink {
    private val chunks = ArrayList<ByteArray>()

    override fun write(bytes: ByteArray) {
        chunks += bytes.copyOf()
    }

    fun toByteArray(): ByteArray {
        val out = ByteArray(chunks.sumOf { it.size })
        var at = 0
        for (c in chunks) { c.copyInto(out, at); at += c.size }
        return out
    }
}

fun assertEquals(expected: Any?, actual: Any?) = kotlin.test.assertEquals(expected, actual)

fun assertEquals(message: String, expected: Any?, actual: Any?) = kotlin.test.assertEquals(expected, actual, message)

fun assertEquals(expected: Double, actual: Double, delta: Double) {
    kotlin.test.assertTrue(abs(expected - actual) <= delta, "expected $expected, actual $actual (delta $delta)")
}

fun assertEquals(expected: Float, actual: Float, delta: Float) {
    kotlin.test.assertTrue(abs(expected - actual) <= delta, "expected $expected, actual $actual (delta $delta)")
}

fun assertTrue(condition: Boolean) = kotlin.test.assertTrue(condition)

fun assertTrue(message: String, condition: Boolean) = kotlin.test.assertTrue(condition, message)

fun assertFalse(condition: Boolean) = kotlin.test.assertFalse(condition)

fun assertNotNull(actual: Any?) {
    kotlin.test.assertNotNull(actual)
}

fun assertNull(actual: Any?) = kotlin.test.assertNull(actual)

fun assertNull(message: String, actual: Any?) = kotlin.test.assertNull(actual, message)

/** 2026-09-17T10:00Z, the hour [humidPm25Parquet] falls in. */
const val HUMID_HOUR_S = 1_789_639_200L

/**
 * #92. Five rows 10 s apart from [HUMID_HOUR_S], with CF=1 PM2.5 10, 250, 40, 41, 60, atmospheric
 * PM2.5 9, 170, 30, 31, 50 and RH 50, 80, null, 120, 50. The last row has a stale sensor. So the
 * corrected PM2.5 has two rows (10 at 50 percent, 250 at 80 percent) and the raw PM2.5 has four.
 */
fun humidPm25Parquet(): ByteArray {
    val out = MemorySink()
    val w = ParquetWriter(
        out,
        listOf(
            ParquetWriter.Column("event_time_utc_ns", ParquetType.INT64, timestampUtc = ParquetLogicalType.TimeUnit.NANOS),
            ParquetWriter.Column("pms_status", ParquetType.INT32),
            ParquetWriter.Column("pm25_atmospheric_ug_m3", ParquetType.INT32),
            ParquetWriter.Column("pm25_cf1_ug_m3", ParquetType.INT32),
            ParquetWriter.Column("relative_humidity_percent", ParquetType.FLOAT),
        ),
        ParquetCodec.UNCOMPRESSED,
        keyValueMetadata = coreS3Footer(),
    )
    w.begin()
    w.rowGroup(
        listOf(
            ColumnValues.Int64(LongArray(5) { (HUMID_HOUR_S + it * 10L) * 1_000_000_000L }, null),
            ColumnValues.Int32(intArrayOf(4, 4, 4, 4, 2), null),
            ColumnValues.Int32(intArrayOf(9, 170, 30, 31, 50), null),
            ColumnValues.Int32(intArrayOf(10, 250, 40, 41, 60), null),
            ColumnValues.Float32(floatArrayOf(50f, 80f, 0f, 120f, 50f), booleanArrayOf(true, true, false, true, true)),
        ),
    )
    w.finish()
    return out.toByteArray()
}

/** The bundled CoreS3 template, the board every device fixture comes from. */
val coreS3Template: space.opensensor.model.BoardTemplate = space.opensensor.model.BundledCatalog.templates.single { it.id.startsWith("cores3-") }

/** The bundled Waveshare template. */
val waveshareTemplate: space.opensensor.model.BoardTemplate = space.opensensor.model.BundledCatalog.templates.single { it.id.startsWith("waveshare-") }

/**
 * Reads every file as the bundled CoreS3 template. The device fixtures predate the bundled dict,
 * so the default [FileTemplates.BUNDLED] gives them rows and no metric values.
 */
val CoreS3Files: FileTemplates = FileTemplates { _, _ -> coreS3Template }

/**
 * A full stac-opensensor v0.1.0 `opensensor` footer value, shaped like `footers/cores3-telemetry-v5.json`,
 * naming [dict], [desc], [intervalMs], [station] and [boot]. A null leaves that field out. Every other
 * field is there so the reader is shown to ignore it.
 */
fun opensensorFooterJson(
    dict: String?,
    desc: String?,
    intervalMs: Long?,
    board: String = "m5stack-cores3",
    schema: String = "cores3-telemetry-v5",
    station: String? = null,
    boot: String? = null,
): String {
    val dictionary = dict?.let { """"dictionary":{"sha256":"$it","version":"$schema"},""" }.orEmpty()
    val template = desc?.let { """"template":{"href":"https://example.invalid/templates/$schema.json","sha256":"$it"},""" }.orEmpty()
    val interval = intervalMs?.let { """"sample_interval_ms":$it,""" }.orEmpty()
    val id = station?.let { """"id":"$it",""" }.orEmpty()
    val bootId = boot?.let { ""","boot_id":"$it"""" }.orEmpty()
    return """{"version":"0.1.0","station":{$id"device_id":"441bf6e26b40","board":"$board","deployment_id":"unknown"},""" +
        """"firmware":{"version":"6.14.0","schema":"$schema"$bootId},$dictionary$template""" +
        """"acquisition":{$interval"time_semantics":"sample instant, UTC","config_id":"default","calibration_id":"unknown"},""" +
        """"file":{"rotation_interval_s":3600,"row_groups":4,"row_group_rows_max":90,"compression":"zstd","purpose":"telemetry"}}"""
}

/** The `opensensor` footer entry of a file written under [template], with its dict, its desc and [intervalMs]. */
fun opensensorFooter(template: space.opensensor.model.BoardTemplate, intervalMs: Long? = 10_000, desc: String? = template.key.desc): Map<String, String> =
    mapOf(OpenSensorFooter.KEY to opensensorFooterJson(template.dict, desc, intervalMs))

/** A footer naming the bundled CoreS3 template and a 10 s cadence, as a current CoreS3 file carries. */
fun coreS3Footer(extra: Map<String, String> = emptyMap()): Map<String, String> = opensensorFooter(coreS3Template) + extra

/**
 * [bytes] rewritten with the bundled CoreS3 dict, template digest and a 10 s cadence in the footer. The
 * device fixtures predate them, and a derived day file without a dict gives way to the raw files. The
 * station, boot, schema and purpose of the file's own footer are kept.
 */
fun withCoreS3Dict(bytes: ByteArray): ByteArray = ParquetFile.wrap(bytes).use { file ->
    val sink = MemorySink()
    val add = OpenSensorFooter.of(coreS3Footer())!!
    val merged = file.metadata.footer?.copy(sampleIntervalMs = add.sampleIntervalMs, dictionarySha256 = add.dictionarySha256, templateSha256 = add.templateSha256) ?: add
    val w = ParquetWriter(
        sink,
        file.leaves.map { ParquetWriter.Column(it.name, it.type, timestampUtc = it.timestampUtcUnit, fieldId = it.fieldId) },
        keyValueMetadata = file.metadata.keyValueMetadata + (OpenSensorFooter.KEY to merged.encode()),
    )
    w.begin()
    w.rowGroup(file.leaves.map { file.readColumn(it) })
    w.finish()
    sink.toByteArray()
}
