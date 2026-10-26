package earth.walkthru.aqsync.data.history

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The fields History reads from a file's `opensensor` footer, the one Parquet key-value entry of the
 * stac-opensensor v0.1.0 footer contract (compact UTF-8 JSON, in the style of GeoParquet `geo`).
 * Only `acquisition.sample_interval_ms`, `dictionary.sha256` and `template.sha256` matter here, every
 * other field is ignored. Each is null when the footer leaves it out.
 */
data class OpenSensorFooter(
    /** `acquisition.sample_interval_ms`, null when absent or not positive. */
    val sampleIntervalMs: Long? = null,
    /** `dictionary.sha256`, the firmware dictionary digest. */
    val dictionarySha256: String? = null,
    /** `template.sha256`, the SHA-256 of the exact board template bytes. */
    val templateSha256: String? = null,
) {
    /** This footer as the compact JSON value of the [KEY] entry, with only the fields it knows. */
    fun encode(): String = buildJsonObject {
        put(VERSION, CURRENT_VERSION)
        dictionarySha256?.let { sha -> putJsonObject(DICTIONARY) { put(SHA256, sha) } }
        templateSha256?.let { sha -> putJsonObject(TEMPLATE) { put(SHA256, sha) } }
        sampleIntervalMs?.let { ms -> putJsonObject(ACQUISITION) { put(SAMPLE_INTERVAL_MS, ms) } }
    }.toString()

    companion object {
        /** The only footer key of the contract. */
        const val KEY = "opensensor"
        const val CURRENT_VERSION = "0.1.0"
        private const val SUPPORTED_MAJOR = "0"
        private const val VERSION = "version"
        private const val DICTIONARY = "dictionary"
        private const val TEMPLATE = "template"
        private const val ACQUISITION = "acquisition"
        private const val SHA256 = "sha256"
        private const val SAMPLE_INTERVAL_MS = "sample_interval_ms"
        private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

        /**
         * The footer of a file with Parquet [keyValues], or null when it has none History can trust. A
         * missing [KEY], JSON that is not an object, an unknown major version, a malformed digest or an
         * interval that is not an integer all mean no footer, the same as a file written without one.
         */
        fun of(keyValues: Map<String, String>): OpenSensorFooter? = keyValues[KEY]?.let(::parse)

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

        private fun sha256(value: JsonPrimitive): String =
            value.takeIf { it.isString }?.content?.takeIf { SHA256_HEX.matches(it) } ?: throw Malformed()
    }

    /** A footer value of the wrong kind, which makes the whole footer untrusted. */
    private class Malformed : Exception()
}
