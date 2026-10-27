package space.opensensor.model

/**
 * The board templates and app profiles the app ships, read from the raw JSON files under
 * `core/model/bundled`. `templates/` is a verbatim copy of opensensor-space-firmware `docs/shared/templates`, one
 * `<board>/<dict>.json` per board and the `index.json` that maps each `dict` to its file and `desc`, so
 * a new digest is a folder copy and a fixture refresh with no code change. Only the current digest of
 * each board is bundled. An unknown digest resolves only through a served template, and then renders
 * with a profile [AppProfile.derive]d from it.
 */
object BundledCatalog {
    private const val TEMPLATES = "templates/"
    private const val TEMPLATE_INDEX = "${TEMPLATES}index.json"
    private const val PROFILES = "profiles/"

    /**
     * Every bundled template, in `index.json` order, each keyed by its `dict` and the `desc` the index
     * gives it. [templatesOf] requires that desc to equal the SHA-256 of the bundled bytes, which the
     * build computes, so the check runs on Android and iOS alike.
     */
    val templates: List<BoardTemplate> = templatesOf(BundledFiles.files)

    /** Every bundled app profile. */
    val profiles: List<AppProfile> = BundledFiles.files.filterKeys { it.startsWith(PROFILES) }.values.map { AppProfile.parse(it) }

    private val bundledIndex = TemplateIndex(templates)

    /**
     * The templates [files] lists in [TEMPLATE_INDEX]. Each file parses with the index `desc` and must
     * carry the index `dict` and `schema`, its bytes must hash to that `desc` in [sha256], and every
     * template file must be in the index.
     */
    internal fun templatesOf(files: Map<String, String>, sha256: Map<String, String> = BundledFiles.sha256): List<BoardTemplate> {
        val index = ModelJson.parseTemplateIndex(files.getValue(TEMPLATE_INDEX))
        val listed = index.map { TEMPLATES + it.href }.toSet()
        val stray = files.keys.filter { it.startsWith(TEMPLATES) && it != TEMPLATE_INDEX && it !in listed }
        require(stray.isEmpty()) { "bundled templates missing from index.json: $stray" }
        return index.map { entry ->
            val text = requireNotNull(files[TEMPLATES + entry.href]) { "index.json names a missing template ${entry.href}" }
            BoardTemplate.parse(text, desc = entry.desc).also {
                require(it.dict == entry.dict) { "${entry.href} carries dict ${it.dict}, index.json says ${entry.dict}" }
                require(it.schema == entry.schema) { "${entry.href} carries schema ${it.schema}, index.json says ${entry.schema}" }
                val digest = sha256[TEMPLATES + entry.href]
                require(digest == entry.desc) { "${entry.href} bytes hash to desc $digest, index.json says ${entry.desc}" }
            }
        }
    }
    private val profilesByDict: Map<String, AppProfile> = byDict(profiles)

    /** [profiles] keyed by dict. Two profiles for one dict fail, so neither wins silently. */
    internal fun byDict(profiles: List<AppProfile>): Map<String, AppProfile> =
        profiles.associateBy { it.dict }.also { require(it.size == profiles.size) { "two bundled profiles share a dict" } }

    /** A [TemplateIndex] over the bundled templates and any [served] ones. */
    fun index(served: Iterable<BoardTemplate> = emptyList()): TemplateIndex =
        if (served.none()) bundledIndex else TemplateIndex(templates, served)

    /** The bundled template for [dict], whether or not the caller has a [desc]. */
    fun template(dict: String, desc: String? = null): BoardTemplate? = bundledIndex.resolve(dict, desc)

    /** The bundled template whose exact bytes hash to [desc], or null when none does. */
    fun templateByDesc(desc: String): BoardTemplate? = templates.firstOrNull { it.key.desc == desc }

    /** The bundled profile for [dict], or null when none is bundled. */
    fun profile(dict: String): AppProfile? = profilesByDict[dict]

    /** What LIVE shows for [dict], or null when no bundled template has it. */
    fun live(dict: String): LiveColumns? = template(dict)?.let { LiveColumns.of(it, AppProfile.select(it, profile(dict))) }

    /**
     * The thing for [dict] and [desc], joined with [profile], the bundled one by default. Null when
     * neither the bundled nor the [served] templates resolve the key.
     */
    fun thing(
        uuid: String,
        dict: String,
        desc: String? = null,
        served: Iterable<BoardTemplate> = emptyList(),
        collection: ThingCollection? = null,
        deviceId: Long? = null,
        h3Cell: String? = null,
        runtimeSampleIntervalS: Double? = null,
        profile: AppProfile? = BundledCatalog.profile(dict),
    ): Thing? {
        val template = index(served).resolve(dict, desc) ?: return null
        return Thing.join(uuid, template, profile, collection, deviceId, h3Cell, runtimeSampleIntervalS)
    }
}
