package space.opensensor.data.history

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The app-private metadata of a derived day file, the one Parquet entry under [KEY]. It is not part of
 * the stac-opensensor footer contract. The key does not start with `opensensor`, and the value has its
 * own [VERSION] and flat members, so nothing that looks for the footer ever matches a day file. It holds
 * only what History reads back to bind a day file to its template. How a derived file is marked will be
 * revisited with bridge question 29 and spec change 27.
 */
data class AppDay(
    /** The shortest positive interval of the day's sources. */
    val sampleIntervalMs: Long? = null,
    /** The dictionary digest every source names, null when they differ. */
    val dict: String? = null,
    /** The template digest every source names, null when they differ. */
    val desc: String? = null,
) {
    /** This entry as compact JSON. */
    fun encode(): String = buildJsonObject {
        put(VERSION_FIELD, VERSION)
        sampleIntervalMs?.let { put(INTERVAL_MS, it) }
        dict?.let { put(DICT, it) }
        desc?.let { put(DESC, it) }
    }.toString()

    /** The day as the footer fields [FileTemplates] binds a file by. */
    fun asFooter(): OpenSensorFooter = OpenSensorFooter(sampleIntervalMs = sampleIntervalMs, dictionarySha256 = dict, templateSha256 = desc)

    companion object {
        const val KEY = "app_day"
        const val VERSION = 1
        private const val VERSION_FIELD = "app_day_version"
        private const val INTERVAL_MS = "interval_ms"
        private const val DICT = "dict"
        private const val DESC = "desc"
        private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

        /** The [KEY] entry of a day file with Parquet [keyValues], null when it is missing or malformed. */
        fun of(keyValues: Map<String, String>): AppDay? = keyValues[KEY]?.let(::parse)

        /**
         * Parses an [encode]d value. Another version, a value that is not an object, an interval that is
         * not a positive integer or a digest that is not 64 lowercase hex all give null.
         */
        fun parse(value: String): AppDay? = try {
            val root = Json.parseToJsonElement(value) as? JsonObject ?: throw Malformed()
            val version = (root[VERSION_FIELD] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
            if (version != VERSION) throw Malformed()
            val interval = root.primitive(INTERVAL_MS)?.let { ms ->
                ms.takeIf { !it.isString }?.longOrNull?.takeIf { it > 0 } ?: throw Malformed()
            }
            AppDay(interval, root.primitive(DICT)?.let(::digest), root.primitive(DESC)?.let(::digest))
        } catch (_: Malformed) {
            null
        } catch (_: IllegalArgumentException) {
            // Not JSON at all. kotlinx serialization throws a subclass of it.
            null
        }

        private fun JsonObject.primitive(name: String): JsonPrimitive? {
            val value = this[name] ?: return null
            return value as? JsonPrimitive ?: throw Malformed()
        }

        private fun digest(value: JsonPrimitive): String =
            value.takeIf { it.isString }?.content?.takeIf { SHA256_HEX.matches(it) } ?: throw Malformed()
    }

    /** A value of the wrong kind, which makes the whole entry untrusted. */
    private class Malformed : Exception()
}
