package earth.walkthru.aqsync.data.history

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The fields the app reads from a file's `opensensor` footer, the one Parquet key-value entry of the
 * stac-opensensor v0.1.0 footer contract (compact UTF-8 JSON, in the style of GeoParquet `geo`). No
 * other footer key is read. Every field not named here is ignored, and each named one is null when the
 * footer leaves it out.
 *
 * A derived day file carries the same JSON shape under [DAY_KEY] instead, with only the cadence and
 * digests, because it cannot fill a value that validates against `footer.json`.
 */
data class OpenSensorFooter(
    /** `acquisition.sample_interval_ms`, null when absent or not positive. */
    val sampleIntervalMs: Long? = null,
    /** `dictionary.sha256`, the firmware dictionary digest. */
    val dictionarySha256: String? = null,
    /** `template.sha256`, the SHA-256 of the exact board template bytes. */
    val templateSha256: String? = null,
    /** `station.id`, the station that wrote the file. */
    val stationId: String? = null,
    /** `firmware.boot_id`, the boot the file's rows belong to. */
    val bootId: String? = null,
    /** `firmware.schema`, the firmware schema name such as `cores3-telemetry-v5`. */
    val schema: String? = null,
    /** `file.purpose`, `telemetry` for station data. */
    val purpose: String? = null,
) {
    /**
     * This footer as compact JSON with only the fields it knows, in the order of the contract. The app
     * writes it under [DAY_KEY] only, never under [KEY], since it never fills a whole valid footer.
     */
    fun encode(): String = buildJsonObject {
        put(VERSION, CURRENT_VERSION)
        if (stationId != null) putJsonObject(STATION) { put(ID, stationId) }
        if (bootId != null || schema != null) {
            putJsonObject(FIRMWARE) {
                schema?.let { put(SCHEMA, it) }
                bootId?.let { put(BOOT_ID, it) }
            }
        }
        dictionarySha256?.let { sha -> putJsonObject(DICTIONARY) { put(SHA256, sha) } }
        templateSha256?.let { sha -> putJsonObject(TEMPLATE) { put(SHA256, sha) } }
        sampleIntervalMs?.let { ms -> putJsonObject(ACQUISITION) { put(SAMPLE_INTERVAL_MS, ms) } }
        purpose?.let { p -> putJsonObject(FILE) { put(PURPOSE, p) } }
    }.toString()

    companion object {
        /** The only footer key of the contract. */
        const val KEY = "opensensor"

        /**
         * The app-local key of a derived day file. It holds the cadence and digests the app reads back
         * from its own day files, in the shape of [KEY]. It is not part of the contract, so an indexer
         * that looks for [KEY] never takes a day file for a broken raw file.
         */
        const val DAY_KEY = "opensensor_app_day"
        const val CURRENT_VERSION = "0.1.0"
        private const val SUPPORTED_MAJOR = "0"
        private const val VERSION = "version"
        private const val DICTIONARY = "dictionary"
        private const val TEMPLATE = "template"
        private const val ACQUISITION = "acquisition"
        private const val STATION = "station"
        private const val FIRMWARE = "firmware"
        private const val FILE = "file"
        private const val ID = "id"
        private const val SCHEMA = "schema"
        private const val BOOT_ID = "boot_id"
        private const val PURPOSE = "purpose"
        private const val SHA256 = "sha256"
        private const val SAMPLE_INTERVAL_MS = "sample_interval_ms"
        private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

        /**
         * The footer of a file with Parquet [keyValues], or null when it has none History can trust. A
         * missing [KEY], JSON that is not an object, an unknown major version, a malformed digest or an
         * interval that is not an integer all mean no footer, the same as a file written without one.
         */
        fun of(keyValues: Map<String, String>): OpenSensorFooter? = keyValues[KEY]?.let(::parse)

        /** The [DAY_KEY] entry of a derived day file with Parquet [keyValues], under the rules of [of]. */
        fun ofDay(keyValues: Map<String, String>): OpenSensorFooter? = keyValues[DAY_KEY]?.let(::parse)

        /** Parses the JSON [value] of a [KEY] entry, null under the rules of [of]. */
        fun parse(value: String): OpenSensorFooter? = try {
            val root = Json.parseToJsonElement(value) as? JsonObject ?: throw Malformed()
            val version = (root[VERSION] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw Malformed()
            if (version.substringBefore('.') != SUPPORTED_MAJOR) throw Malformed()
            OpenSensorFooter(
                sampleIntervalMs = root.member(ACQUISITION, SAMPLE_INTERVAL_MS)?.let { ms ->
                    if (ms.isString) throw Malformed()
                    ms.longOrNull ?: throw Malformed()
                }?.takeIf { it > 0 },
                dictionarySha256 = root.member(DICTIONARY, SHA256)?.let(::sha256),
                templateSha256 = root.member(TEMPLATE, SHA256)?.let(::sha256),
                stationId = root.member(STATION, ID)?.let(::text),
                bootId = root.member(FIRMWARE, BOOT_ID)?.let(::text),
                schema = root.member(FIRMWARE, SCHEMA)?.let(::text),
                purpose = root.member(FILE, PURPOSE)?.let(::text),
            )
        } catch (_: Malformed) {
            null
        } catch (_: IllegalArgumentException) {
            // Not JSON at all. kotlinx serialization throws a subclass of it.
            null
        }

        /** Member [field] of object [obj], null when either is left out. Throws [Malformed] when one is not the right kind. */
        private fun JsonObject.member(obj: String, field: String): JsonPrimitive? {
            val parent = this[obj] ?: return null
            if (parent !is JsonObject) throw Malformed()
            val value = parent[field] ?: return null
            return value as? JsonPrimitive ?: throw Malformed()
        }

        private fun text(value: JsonPrimitive): String =
            value.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() } ?: throw Malformed()

        private fun sha256(value: JsonPrimitive): String =
            value.takeIf { it.isString }?.content?.takeIf { SHA256_HEX.matches(it) } ?: throw Malformed()
    }

    /** A footer value of the wrong kind, which makes the whole footer untrusted. */
    private class Malformed : Exception()
}
