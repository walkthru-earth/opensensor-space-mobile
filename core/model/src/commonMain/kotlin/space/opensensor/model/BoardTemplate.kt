package space.opensensor.model

/**
 * The key of a board template. [dict] is the firmware dictionary digest from `info` and `HELLO`, always
 * present. [desc] is the SHA-256 of the exact template bytes, null when the caller has none. A unit or
 * range fix changes [desc] but not [dict]. See [TemplateIndex].
 */
data class TemplateKey(val dict: String, val desc: String? = null) {
    /** True when [desc] is [prefix] or starts with it. A device names its template by the first [DESC_PREFIX_HEX] hex. */
    fun matchesDesc(prefix: String): Boolean =
        prefix.length >= DESC_PREFIX_HEX && desc?.startsWith(prefix.lowercase()) == true

    companion object {
        /** Hex characters of the desc that `info` and `HELLO` carry. */
        const val DESC_PREFIX_HEX = 16
    }
}

/**
 * Finds the template a device or file names. A [TemplateKey.desc] match among [bundled] and [served]
 * wins, then the bundled template for the same [TemplateKey.dict], whether or not it carries a desc. The
 * desc asked for may be the full 64 hex or the 16 hex prefix a device reports. A served template with
 * another desc is never used as a stand-in, since its units or ranges may differ.
 */
class TemplateIndex(bundled: Iterable<BoardTemplate>, served: Iterable<BoardTemplate> = emptyList()) {
    private val withDesc: List<BoardTemplate> = (served + bundled).filter { it.key.desc != null }
    private val bundledByDict: Map<String, BoardTemplate> = bundled.associateBy { it.dict }

    init {
        require(bundledByDict.size == bundled.count()) { "two bundled templates share a dict" }
    }

    /** The template for [dict] and [desc], or null when none is known. */
    fun resolve(dict: String, desc: String? = null): BoardTemplate? =
        desc?.let(::byDesc)?.takeIf { it.dict == dict } ?: bundledByDict[dict]

    /** The template whose desc is [desc] or starts with it, null when none is known. */
    fun byDesc(desc: String): BoardTemplate? = withDesc.firstOrNull { it.key.matchesDesc(desc) }
}

/**
 * What a firmware build knows about its columns, parsed from a stac-opensensor v0.1.0 board template
 * (`"type": "OpenSensorTemplate"`). It carries only template facts, see [AppProfile] for the app's
 * presentation choices and [Thing] for the join.
 */
data class BoardTemplate(
    val key: TemplateKey,
    /** Template `id`, the firmware schema name such as `cores3-telemetry-v5`. */
    val id: String,
    /** Firmware schema name from `firmware.schema`. */
    val schema: String,
    val title: String?,
    val keywords: List<String>,
    val instruments: List<String>,
    /**
     * `opensensor:sample_interval_s`, only a default. The runtime interval from `info` or a file
     * footer wins, see [Thing.sampleIntervalFor].
     */
    val defaultSampleIntervalS: Double,
    /** Lowest sync protocol the board speaks, `firmware.proto_min`. */
    val protoMin: Int,
    /** `table:primary_datetime`, the column that times each row. */
    val primaryDatetime: String?,
    /** Every column in template order, each with its LIVE key and firmware unit label filled in. */
    val datastreams: List<Datastream>,
    /** Column name to LIVE key, `firmware.live_keys`. */
    val liveKeys: Map<String, String>,
    /** Href of the firmware dictionary `describedby` link. Shown only, never fetched or trusted. The dict digest is the key. */
    val dictionaryHref: String?,
) {
    init {
        val names = datastreams.map { it.name }
        require(names.size == names.toSet().size) { "template $id lists a column twice" }
        require(defaultSampleIntervalS > 0) { "template $id has a sample interval of $defaultSampleIntervalS" }
    }

    private val byName: Map<String, Datastream> = datastreams.associateBy { it.name }
    private val byLiveKey: Map<String, String> = liveKeys.entries.associate { (column, key) -> key to column }

    val dict: String get() = key.dict

    fun datastream(name: String): Datastream? = byName[name]

    /** The column a LIVE [key] carries on this template, or null for a key it does not map. */
    fun columnForLiveKey(key: String): String? = byLiveKey[key]

    companion object {
        /**
         * Parses a board template. [desc] is the SHA-256 the caller computed over the exact bytes it
         * received, never over a re-encoded copy. Unknown fields are ignored.
         *
         * @throws IllegalArgumentException when the JSON is not a valid board template.
         */
        fun parse(json: String, desc: String? = null): BoardTemplate = ModelJson.parseTemplate(json, desc)
    }
}
