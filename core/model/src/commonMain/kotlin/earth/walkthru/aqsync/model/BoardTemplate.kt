package earth.walkthru.aqsync.model

/**
 * The key of a board template. [dict] is the firmware dictionary digest from `info` and `HELLO`, always
 * present. [desc] is the SHA-256 of the exact template bytes, null for a bundled template, which is
 * keyed by [dict] alone. A unit or range fix changes [desc] but not [dict]. See [TemplateIndex].
 */
data class TemplateKey(val dict: String, val desc: String? = null)

/**
 * Finds the template a device or file names. A [TemplateKey.desc] match wins, then the bundled
 * template for the same [TemplateKey.dict]. A served template with another desc is never used as a
 * stand-in, since its units or ranges may differ.
 */
class TemplateIndex(templates: Iterable<BoardTemplate>) {
    private val byDesc: Map<String, BoardTemplate> =
        templates.mapNotNull { t -> t.key.desc?.let { it to t } }.toMap()
    private val bundledByDict: Map<String, BoardTemplate> =
        templates.filter { it.key.desc == null }.associateBy { it.dict }

    /** The template for [dict] and [desc], or null when none is known. */
    fun resolve(dict: String, desc: String? = null): BoardTemplate? =
        desc?.let { byDesc[it] }?.takeIf { it.dict == dict } ?: bundledByDict[dict]
}

/**
 * What a firmware build knows about its columns, parsed from a stac-opensensor v0.1.0 board template
 * (`"type": "OpenSensorTemplate"`). It carries only template facts, see [AppProfile] for the app's
 * presentation choices and [Station] for the join.
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
     * footer wins, see [Station.sampleIntervalFor].
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
    /** Href of the firmware dictionary `describedby` link. */
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
