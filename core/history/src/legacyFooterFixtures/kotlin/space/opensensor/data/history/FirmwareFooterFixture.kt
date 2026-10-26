package space.opensensor.data.history

// Test-only. Shared as a source directory by the :core:history tests and the :app unit tests. Never part
// of a release build.

/**
 * A full stac-opensensor v0.1.0 `opensensor` footer value, shaped member for member like the footer
 * firmware writes (aq-parquet #105), naming [dict], [desc], [intervalMs], [thing], [purpose] and [boot]. A null
 * leaves that member out. The firmware version is a placeholder, so no test pins a release string.
 * Every member the app does not read is there so the reader is shown to ignore it.
 */
fun opensensorFooterJson(
    dict: String?,
    desc: String?,
    intervalMs: Long?,
    board: String = "m5stack-cores3",
    schema: String = "cores3-telemetry-v5",
    thing: String? = null,
    purpose: String? = "telemetry",
    boot: String = "0123456789abcdef0123456789abcdef",
): String {
    val id = thing?.let { """"id":"$it",""" }.orEmpty()
    val dictionary = dict?.let {
        """"dictionary":{"sha256":"$it","version":"$schema","uri":"https://example.invalid/dictionaries/$schema.inc"},"""
    }.orEmpty()
    val template = desc?.let { """"template":{"href":"https://example.invalid/templates/$board/$it.json","sha256":"$it"},""" }.orEmpty()
    val interval = intervalMs?.let { """"sample_interval_ms":$it,""" }.orEmpty()
    val filePurpose = purpose?.let { ""","purpose":"$it"""" }.orEmpty()
    return """{"version":"0.1.0","station":{$id"device_id":"0123456789ab","board":"$board","deployment_id":"unknown"},""" +
        """"firmware":{"version":"test-firmware","schema":"$schema","boot_id":"$boot"},""" +
        dictionary + template +
        """"acquisition":{$interval"time_semantics":"sample instant, UTC","config_id":"default","config":"{}","calibration_id":"unknown"},""" +
        """"file":{"rotation_interval_s":3600,"row_groups":4,"row_group_rows_max":90,"compression":"LZ4_RAW"$filePurpose}}"""
}
