package space.opensensor.data.history

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The fields the app reads from a file's `opensensor` footer, the one Parquet key-value entry of the
 * stac-opensensor v0.1.0 footer contract (compact UTF-8 JSON, in the style of GeoParquet `geo`). The
 * standard GeoParquet `geo` key may sit beside it and is not read here. No other footer key is read.
 * A footer is trusted only when its `version` is 0.1 with any patch and it has every member
 * `footer.json` requires ([REQUIRED]). Members it does not require, and fields not named here, are
 * ignored. So a parsed footer always has [dictionarySha256], [templateSha256], [thingId], [mobility]
 * and [purpose]. The app never writes this key. A derived day file carries [AppDay] instead,
 * and [AppDay.asFooter] is the one footer without a thing.
 */
data class OpenSensorFooter(
    /** `acquisition.sample_interval_ms`, null when not positive, so the template default applies. */
    val sampleIntervalMs: Long? = null,
    /** `dictionary.sha256`, the firmware dictionary digest. Always set on a parsed footer. */
    val dictionarySha256: String? = null,
    /** `template.sha256`, the SHA-256 of the exact board template bytes. Always set on a parsed footer. */
    val templateSha256: String? = null,
    /**
     * `template.href`, where the exact template bytes can be fetched. [REQUIRED] makes it present and
     * not JSON null on a parsed footer, and it is null here only when it is not a non empty string, a
     * number or an empty string for example. Only a hint, [TemplateHrefs] fetches it and keeps the bytes
     * only when they hash to [templateSha256], so a value of the wrong kind never drops the rest of the
     * footer.
     */
    val templateHref: String? = null,
    /** `thing.id`, the thing that wrote the file. Always set on a parsed footer. */
    val thingId: String? = null,
    /** `thing.mobility`. Always set on a parsed footer. */
    val mobility: Mobility? = null,
    /** `file.purpose`, `telemetry` for thing data. Always set on a parsed footer. */
    val purpose: String? = null,
    /**
     * `file.rotation_interval_s`, the length of the file's rotation window, one of [ROTATION_WINDOWS_S].
     * Null when of another kind or another value, since the window then cannot be placed. Such a value
     * never drops the rest of the footer.
     */
    val rotationIntervalS: Long? = null,
) {
    companion object {
        /** The only footer key of the contract. */
        const val KEY = "opensensor"

        /**
         * The footer versions the app reads. While the contract is 0.x a minor bump breaks readers, so
         * the major and the minor must be 0.1 and only the patch may differ (stac-opensensor PROFILE.md,
         * Versioning).
         */
        private val SUPPORTED_VERSION = Regex("^0\\.1\\.[0-9]+$")
        private const val VERSION = "version"
        private const val DICTIONARY = "dictionary"
        private const val TEMPLATE = "template"
        private const val ACQUISITION = "acquisition"
        private const val THING = "thing"
        private const val MOBILITY = "mobility"
        private const val FILE = "file"
        private const val ID = "id"
        private const val PURPOSE = "purpose"
        private const val SHA256 = "sha256"
        private const val HREF = "href"
        private const val SAMPLE_INTERVAL_MS = "sample_interval_ms"
        private const val ROTATION_INTERVAL_S = "rotation_interval_s"

        /**
         * Every member stac-opensensor v0.1.0 `footer.json` requires below the top level, object by
         * object. Each object must be a JSON object, and a footer without one of these members, or with
         * JSON null in its place, is not trusted. The top level `version` is checked on its own.
         *
         * This check enforces presence and non null only, never the types `footer.json` gives. A member
         * the app reads is checked for its kind where [parse] reads it. One that holds only a hint, like
         * `template.href` or `file.rotation_interval_s`, is left out when of the wrong kind and the
         * footer still parses. Members the app does not read are not checked further.
         */
        val REQUIRED: Map<String, List<String>> = mapOf(
            THING to listOf(ID, "device_id", "board", MOBILITY),
            "firmware" to listOf(VERSION, "schema", "boot_id"),
            DICTIONARY to listOf(SHA256),
            TEMPLATE to listOf(HREF, SHA256),
            ACQUISITION to listOf(SAMPLE_INTERVAL_MS, "time_semantics"),
            FILE to listOf(ROTATION_INTERVAL_S, "row_groups", "row_group_rows_max", "compression", PURPOSE),
        )

        /** The rotation windows the stac-opensensor profile allows. Each divides an hour. */
        val ROTATION_WINDOWS_S: Set<Long> = setOf(600L, 900L, 1800L, 3600L)
        private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

        /**
         * The footer of a file with Parquet [keyValues], or null when it has none History can trust. A
         * missing [KEY], JSON that is not an object, a version other than 0.1 with any patch, a missing
         * [REQUIRED] member, a malformed digest, an empty `thing.id` or `file.purpose`, a mobility other
         * than `fixed` or `moving` or a sample interval that is not an integer all mean no footer, the
         * same as a file written without one.
         */
        fun of(keyValues: Map<String, String>): OpenSensorFooter? = keyValues[KEY]?.let(::parse)

        /** Parses the JSON [value] of a [KEY] entry, null under the rules of [of]. */
        fun parse(value: String): OpenSensorFooter? = try {
            val root = Json.parseToJsonElement(value) as? JsonObject ?: throw Malformed()
            val version = (root[VERSION] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw Malformed()
            if (!SUPPORTED_VERSION.matches(version)) throw Malformed()
            for ((obj, fields) in REQUIRED) {
                val parent = root[obj] as? JsonObject ?: throw Malformed()
                for (field in fields) if (parent[field] == null || parent[field] is JsonNull) throw Malformed()
            }
            OpenSensorFooter(
                sampleIntervalMs = root.member(ACQUISITION, SAMPLE_INTERVAL_MS)?.let { ms ->
                    if (ms.isString) throw Malformed()
                    ms.longOrNull ?: throw Malformed()
                }?.takeIf { it > 0 },
                dictionarySha256 = root.member(DICTIONARY, SHA256)?.let(::sha256),
                templateSha256 = root.member(TEMPLATE, SHA256)?.let(::sha256),
                templateHref = (root[TEMPLATE] as? JsonObject)?.get(HREF)
                    ?.let { it as? JsonPrimitive }
                    ?.takeIf { it.isString }
                    ?.content
                    ?.takeIf { it.isNotEmpty() },
                thingId = text(root.member(THING, ID) ?: throw Malformed()),
                mobility = Mobility.of(text(root.member(THING, MOBILITY) ?: throw Malformed())) ?: throw Malformed(),
                purpose = text(root.member(FILE, PURPOSE) ?: throw Malformed()),
                // A window of the wrong kind or value is treated like one outside the set. The rest of
                // the footer survives, so History keeps its digests and the File Item reports the window.
                rotationIntervalS = (root[FILE] as? JsonObject)?.get(ROTATION_INTERVAL_S)
                    ?.let { it as? JsonPrimitive }
                    ?.takeIf { !it.isString }
                    ?.longOrNull
                    ?.takeIf { it in ROTATION_WINDOWS_S },
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

    /** `thing.mobility`, whether a Thing stays in one H3 cell or moves. */
    enum class Mobility(val wire: String) {
        FIXED("fixed"),
        MOVING("moving"),
        ;

        companion object {
            fun of(value: String): Mobility? = entries.firstOrNull { it.wire == value }
        }
    }

    /** A footer value of the wrong kind, which makes the whole footer untrusted. */
    private class Malformed : Exception()
}
