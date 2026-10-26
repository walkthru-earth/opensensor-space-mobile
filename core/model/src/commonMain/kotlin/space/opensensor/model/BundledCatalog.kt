package space.opensensor.model

/**
 * The board templates and app profiles the app ships, read from the raw JSON files under
 * `core/model/bundled`. Templates are keyed by their `firmware.dict` digest and copied verbatim from
 * the firmware, so a new digest is a file swap and a fixture refresh with no code change. Only the
 * current digest of each board is bundled. An unknown digest resolves only through a served template,
 * and then renders with a profile [AppProfile.derive]d from it.
 */
object BundledCatalog {
    private const val TEMPLATES = "templates/"
    private const val PROFILES = "profiles/"

    /** Every bundled template. Bundled bytes carry no desc, a lookup by dict finds them. */
    val templates: List<BoardTemplate> = BundledFiles.files.filterKeys { it.startsWith(TEMPLATES) }.values.map { BoardTemplate.parse(it) }

    /** Every bundled app profile. */
    val profiles: List<AppProfile> = BundledFiles.files.filterKeys { it.startsWith(PROFILES) }.values.map { AppProfile.parse(it) }

    private val bundledIndex = TemplateIndex(templates)
    private val profilesByDict: Map<String, AppProfile> = profiles.associateBy { it.dict }

    init {
        require(profilesByDict.size == profiles.size) { "two bundled profiles share a dict" }
    }

    /** A [TemplateIndex] over the bundled templates and any [served] ones. */
    fun index(served: Iterable<BoardTemplate> = emptyList()): TemplateIndex =
        if (served.none()) bundledIndex else TemplateIndex(templates, served)

    /** The bundled template for [dict], whether or not the caller has a [desc]. */
    fun template(dict: String, desc: String? = null): BoardTemplate? = bundledIndex.resolve(dict, desc)

    /** The bundled profile for [dict], or null when none is bundled. */
    fun profile(dict: String): AppProfile? = profilesByDict[dict]

    /** What LIVE shows for [dict], or null when no bundled template has it. */
    fun live(dict: String): LiveColumns? = template(dict)?.let { LiveColumns.of(it, AppProfile.select(it, profile(dict))) }

    /**
     * The station for [dict] and [desc], joined with the bundled profile. Null when neither the bundled
     * nor the [served] templates resolve the key.
     */
    fun station(
        uuid: String,
        dict: String,
        desc: String? = null,
        served: Iterable<BoardTemplate> = emptyList(),
        collection: StationCollection? = null,
        deviceId: Long? = null,
        h3Cell: String? = null,
        runtimeSampleIntervalS: Double? = null,
    ): Station? {
        val template = index(served).resolve(dict, desc) ?: return null
        return Station.join(uuid, template, profile(dict), collection, deviceId, h3Cell, runtimeSampleIntervalS)
    }
}
