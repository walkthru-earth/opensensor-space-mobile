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
 * The one rule that finds the template a device or a file names, for LIVE ([BundledCatalog.thing]) and
 * History (`FileTemplates`) alike (#259). Each template has its own SHA-256, so a desc wins everywhere.
 *
 * - With a desc, only a template whose desc it is resolves, a served one before a bundled one. The desc
 *   is the full 64 hex of a file footer, which matches exactly, or the 16 hex prefix a device reports
 *   ([TemplateKey.matchesDesc]). A match whose dict differs from the dict asked for is not used. A desc
 *   no template has resolves to null, never to another template of its dict, whose units or ranges may
 *   differ, so the caller can fetch the template the desc names.
 * - Without a desc, the newest served template of the dict resolves, else the bundled one.
 */
object TemplateIndex {
    /**
     * The template for [dict] and [desc] among [served], oldest first, and then [bundled], or null when
     * none is known. Either key is null when the caller has none.
     */
    fun resolve(dict: String?, desc: String?, served: List<BoardTemplate>, bundled: List<BoardTemplate>): BoardTemplate? {
        if (desc != null) return byDesc(served, dict, desc) ?: byDesc(bundled, dict, desc)
        if (dict == null) return null
        return served.lastOrNull { it.dict == dict } ?: bundled.firstOrNull { it.dict == dict }
    }

    private fun byDesc(templates: List<BoardTemplate>, dict: String?, desc: String): BoardTemplate? =
        templates.firstOrNull { it.key.matchesDesc(desc) && (dict == null || it.dict == dict) }
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
