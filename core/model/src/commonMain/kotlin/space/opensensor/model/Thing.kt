package space.opensensor.model

/**
 * What the app reads from a thing Collection (stac-opensensor thing profile). Column titles,
 * the column it opens on, the scale each column points at and the scales themselves. [dict] and [desc] come from the
 * `file:checksum` of its two `describedby` links, the dictionary and the board template.
 */
data class ThingCollection(
    val id: String,
    val title: String? = null,
    val license: String? = null,
    val sampleIntervalS: Double? = null,
    /** `opensensor:primary`, the column the thing opens on. It wins over the template's. */
    val primary: String? = null,
    val scales: Map<String, Scale> = emptyMap(),
    val columnTitles: Map<String, String> = emptyMap(),
    /** Column name to the id of its scale in [scales]. */
    val columnScales: Map<String, String> = emptyMap(),
    val dict: String? = null,
    val desc: String? = null,
) {
    companion object {
        /**
         * Parses a thing Collection. Unknown fields are ignored.
         *
         * @throws IllegalArgumentException when the JSON is not a thing Collection.
         */
        fun parse(json: String): ThingCollection = ModelJson.parseCollection(json)
    }
}

/** One datastream of a [Thing], with the presentation its [AppProfile] gives it. */
data class ThingDatastream(
    val datastream: Datastream,
    /** The label by language, see [AppProfile.derive]. */
    val labels: LocalizedText,
    /** Decimals the value shows with. */
    val decimals: Int,
    /** The scale the value is judged on, or null for a neutral column. */
    val scale: Scale? = null,
    /** Unit text the screens show, null for a unitless column. */
    val displayUnit: String? = datastream.unit,
) {
    val name: String get() = datastream.name

    /** The label in [language], else English, else the untagged one. */
    fun label(language: String): String = labels.get(language)
}

/**
 * A thing joined from one board template, one app profile and the thing Collection. Construct it
 * with [join].
 */
class Thing private constructor(
    /** Thing UUID from the file footer or `info`. */
    val uuid: String,
    val deviceId: Long?,
    /** H3 cell of the owner's chosen resolution, never a coordinate. */
    val h3Cell: String?,
    val template: BoardTemplate,
    /** The profile [AppProfile.select] derives from [template], [collection] and the bundled overlay. */
    val profile: AppProfile,
    val collection: ThingCollection?,
    /** The interval the device reports at runtime, from `info` or the file footer. */
    val runtimeSampleIntervalS: Double?,
) {
    val dict: String get() = template.dict

    /** Seconds between samples for the thing as a whole, [sampleIntervalFor] without a file footer. */
    val sampleIntervalS: Double get() = sampleIntervalFor(footerSampleIntervalMs = null)

    /**
     * Seconds between samples in one file. The file footer `acquisition.sample_interval_ms` wins, then the runtime
     * value, then the thing Collection, then the template default. Valid and expected row counts
     * use this. A value of zero or less counts as absent.
     */
    fun sampleIntervalFor(footerSampleIntervalMs: Long?): Double =
        footerSampleIntervalMs?.takeIf { it > 0 }?.let { it / MS_PER_S }
            ?: runtimeSampleIntervalS?.takeIf { it > 0 }
            ?: collection?.sampleIntervalS?.takeIf { it > 0 }
            ?: template.defaultSampleIntervalS

    /** Every datastream in template order, whatever its role. The profile picks what is shown first. */
    val datastreams: List<ThingDatastream> = template.datastreams.map { ds ->
        ThingDatastream(
            datastream = ds,
            labels = profile.labelOf(ds),
            decimals = profile.decimalsOf(ds),
            scale = profile.scales[ds.name],
            displayUnit = profile.displayUnitOf(ds),
        )
    }

    private val byName: Map<String, ThingDatastream> = datastreams.associateBy { it.name }

    /** The column the thing opens on, [AppProfile.primary], or null when it measures nothing. */
    val primary: ThingDatastream? = profile.primary?.let { byName[it] }

    fun datastream(name: String): ThingDatastream? = byName[name]

    /** What LIVE shows for this thing. */
    val live: LiveColumns = LiveColumns.of(template, profile)

    /** The datastream a LIVE [key] carries, or null for a key the template does not map. */
    fun datastreamForLiveKey(key: String): ThingDatastream? = template.columnForLiveKey(key)?.let { byName[it] }

    companion object {
        private const val MS_PER_S = 1000.0

        /**
         * Joins [template] with the overlay [profile] and the thing [collection] through
         * [AppProfile.select]. A profile for another dictionary is ignored, so an unknown digest still renders.
         */
        fun join(
            uuid: String,
            template: BoardTemplate,
            profile: AppProfile? = null,
            collection: ThingCollection? = null,
            deviceId: Long? = null,
            h3Cell: String? = null,
            runtimeSampleIntervalS: Double? = null,
        ): Thing = Thing(
            uuid = uuid,
            deviceId = deviceId,
            h3Cell = h3Cell,
            template = template,
            profile = AppProfile.select(template, profile, collection),
            collection = collection,
            runtimeSampleIntervalS = runtimeSampleIntervalS,
        )
    }
}
