package earth.walkthru.aqsync.data.history

// Test-only, in the legacyFooterFixtures source set with [migrateLegacyFooter]. Never part of a release build.

/**
 * The unprefixed footer keys firmware wrote before the stac-opensensor `opensensor` JSON. The app reads
 * none of them. Only this fixture set names them, for the migration and for the tests that prove the
 * app ignores them.
 */
object LegacyFooterKeys {
    const val STATION_ID = "station_id"
    const val BOOT_ID = "boot_id"
    const val SCHEMA_VERSION = "schema_version"
    const val PURPOSE = "purpose"
    const val DICTIONARY_SHA256 = "dictionary_sha256"
    const val TEMPLATE_SHA256 = "template_sha256"
    const val SAMPLE_INTERVAL_MS = "sample_interval_ms"
    private const val ANCHOR = "reconciliation_anchor_"

    /** True for any old key, the dropped `reconciliation_anchor_*` clock anchors included. */
    fun isLegacy(key: String): Boolean =
        key in setOf(STATION_ID, BOOT_ID, SCHEMA_VERSION, PURPOSE, DICTIONARY_SHA256, TEMPLATE_SHA256, SAMPLE_INTERVAL_MS) || key.startsWith(ANCHOR)

    /** An old footer with one entry per value given. */
    fun footer(
        dict: String? = null,
        desc: String? = null,
        intervalMs: Long? = null,
        station: String? = null,
        boot: String? = null,
        schema: String? = null,
        purpose: String? = null,
    ): Map<String, String> = listOfNotNull(
        dict?.let { DICTIONARY_SHA256 to it },
        desc?.let { TEMPLATE_SHA256 to it },
        intervalMs?.let { SAMPLE_INTERVAL_MS to it.toString() },
        station?.let { STATION_ID to it },
        boot?.let { BOOT_ID to it },
        schema?.let { SCHEMA_VERSION to it },
        purpose?.let { PURPOSE to it },
    ).toMap()

    /** The old footer clock anchor keys, which decision 25 drops. */
    fun anchor(monoUs: Long, utcNs: Long, source: Int): Map<String, String> = mapOf(
        "${ANCHOR}mono_us" to monoUs.toString(),
        "${ANCHOR}utc_ns" to utcNs.toString(),
        "${ANCHOR}source" to source.toString(),
    )
}
