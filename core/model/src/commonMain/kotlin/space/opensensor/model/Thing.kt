package space.opensensor.model

/**
 * What the app reads from a thing Collection (stac-opensensor thing profile). Column titles,
 * the scale each column points at and the scales themselves. [dict] and [desc] come from the
 * `file:checksum` of its two `describedby` links, the dictionary and the board template.
 */
data class ThingCollection(
    val id: String,
    val title: String? = null,
    val license: String? = null,
    val sampleIntervalS: Double? = null,
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

/** One datastream of a [Thing], with the presentation the thing and its profile give it. */
data class ThingDatastream(
    val datastream: Datastream,
    /** Column `title` from the thing Collection. */
    val title: String?,
    /** Profile label, if the profile has one. */
    val profileLabel: LocalizedText?,
    val displayStep: Double,
    val scale: Scale?,
    /** Unit text from the profile, else the template's raw unit, null for a unitless column. */
    val displayUnit: String? = datastream.unit,
    /** Health scale id from the profile, or null for a neutral column. */
    val scaleId: String? = null,
) {
    val name: String get() = datastream.name

    /** The profile label in [language], else the thing title, else the column name. */
    fun label(language: String): String = profileLabel?.get(language) ?: title ?: datastream.name
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
    /** The bundled profile for [dict], else one derived from [template]. */
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
        val scaleId = collection?.columnScales?.get(ds.name)
        ThingDatastream(
            datastream = ds,
            title = collection?.columnTitles?.get(ds.name),
            profileLabel = profile.labels[ds.name],
            displayStep = profile.displayStepOf(ds),
            scale = scaleId?.let { collection?.scales?.get(it) },
            displayUnit = profile.displayUnit[ds.name] ?: ds.unit,
            scaleId = profile.scales[ds.name],
        )
    }

    private val byName: Map<String, ThingDatastream> = datastreams.associateBy { it.name }

    /** The profile's primary columns that this template has and the profile does not hide, in profile order. */
    val primary: List<ThingDatastream> = profile.primary.filter { it !in profile.hidden }.mapNotNull { byName[it] }

    fun datastream(name: String): ThingDatastream? = byName[name]

    /** What LIVE shows for this thing. */
    val live: LiveColumns = LiveColumns.of(template, profile)

    /** The datastream a LIVE [key] carries, or null for a key the template does not map. */
    fun datastreamForLiveKey(key: String): ThingDatastream? = template.columnForLiveKey(key)?.let { byName[it] }

    companion object {
        private const val MS_PER_S = 1000.0

        /**
         * Joins [template] with [profile] and the thing [collection]. A profile for another
         * dictionary, a missing one, or one with none of its primary columns in [template] is replaced
         * by [AppProfile.derive], so an unknown digest still renders.
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
            profile = AppProfile.select(template, profile, collection?.columnTitles.orEmpty()),
            collection = collection,
            runtimeSampleIntervalS = runtimeSampleIntervalS,
        )
    }
}
