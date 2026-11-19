package space.opensensor.model

/**
 * The quantity a datastream observes, the same on every board. [id] is the template's
 * `opensensor:property`, a shared vocabulary URI (NERC P07 or a NERC CF standard name) or the local
 * `urn:walkthru-earth:property:<quantity>`. It never names a board, that lives in [Sensor]. Join on
 * [id], never on [standardName].
 */
data class ObservedProperty(val id: String, val standardName: String? = null) {
    init {
        require(id.isNotEmpty()) { "an observed property has an empty id" }
    }
}

/**
 * The instrument that produces a datastream. [procedure] is the template's `opensensor:procedure`,
 * the model of the sensor that measures it. [board] is the board template id that carries it, since
 * the same sensor model on two boards can be wired and calibrated differently.
 */
data class Sensor(val procedure: String, val board: String? = null) {
    init {
        require(procedure.isNotEmpty()) { "a sensor has an empty procedure" }
    }
}

/**
 * One column of a board template, or one datastream the phone derives from columns. It carries only
 * template facts, the column's display hints included ([title], [decimals], [group], [scale] and
 * the rest). Owner titles and scales come from the thing Collection and labels and display steps from
 * the app profile, see [ThingDatastream]. A hint the template leaves out stays null or empty, the
 * fallback to the [PropertyRegistry] is the caller's.
 *
 * [name] is the datastream id. For a stored column it is the column name. For a derived datastream
 * ([derived] set) it is its own id and [sourceColumns] lists the stored columns it reads.
 */
data class Datastream(
    val name: String,
    /** Parquet type as the Table extension spells it, such as `int32` or `float`. */
    val type: String,
    val role: Role?,
    /** UCUM code from core `unit`, or null for a unitless column. */
    val unit: String? = null,
    /** The board-neutral quantity, or null when the template names none. */
    val property: ObservedProperty? = null,
    /** The instrument and board, or null when the template names no procedure. */
    val sensor: Sensor? = null,
    val fieldId: Int? = null,
    val validWhen: ValidityGate? = null,
    /** Warm-up seconds after power on. Readings count but are flagged as settling. Not a gate. */
    val settleSeconds: Double = 0.0,
    /** As written in the template, or null when it names none. See [rollup]. */
    val aggregate: Aggregate? = null,
    val plausibleRange: PlausibleRange? = null,
    val classes: List<CodeClass> = emptyList(),
    val derived: Derivation? = null,
    /** LIVE JSON key from the template `firmware.live_keys`, or null when LIVE does not send it. */
    val liveKey: String? = null,
    /** Firmware unit string from `firmware.unit_labels`, where it differs from [unit]. */
    val unitLabel: String? = null,
    /** Column `title`, the default label as plain text. */
    val title: String? = null,
    /** `opensensor:title_i18n`, language code to label for every other language. */
    val titleI18n: Map<String, String> = emptyMap(),
    /** Column `description`, the default longer text as plain text. */
    val description: String? = null,
    /** `opensensor:description_i18n`, language code to description for every other language. */
    val descriptionI18n: Map<String, String> = emptyMap(),
    /** `opensensor:decimals`, digits after the point when a value is shown, 0 to 9. Display only. */
    val decimals: Int? = null,
    /** `opensensor:group`, the display group. */
    val group: DisplayGroup? = null,
    /** `opensensor:scale`, the id of a scale in the template or Collection `opensensor:scales`. */
    val scale: String? = null,
    /** `classification:bitfields` of a `{bitmask}` column, one entry per documented field. */
    val bitfields: List<BitField> = emptyList(),
) {
    init {
        require(decimals == null || decimals in 0..MAX_DECIMALS) { "column $name shows $decimals decimals" }
    }

    val isDerived: Boolean get() = derived != null

    /** The stored columns this datastream reads, itself for a stored column. */
    val sourceColumns: List<String> get() = derived?.from ?: listOf(name)

    /** The aggregate the tiers apply. Missing or unknown rolls up as [Aggregate.POINT]. */
    val rollup: Aggregate get() = aggregate?.rollup ?: Aggregate.POINT

    /** True for an integer Parquet type, whose values show without decimals by default. */
    val isInteger: Boolean get() = type in INTEGER_TYPES

    /** The code class of [value], or null for a column without classes or an unlisted value. */
    fun classOf(value: Long): CodeClass? = classes.firstOrNull { it.value == value }

    /** The template title in [language], else the plain [title], else null. The registry label is the caller's next step. */
    fun titleIn(language: String): String? = titleI18n[language] ?: title

    /** The template description in [language], else the plain [description], else null. */
    fun descriptionIn(language: String): String? = descriptionI18n[language] ?: description

    companion object {
        /** The most digits after the point a display hint may ask for. */
        const val MAX_DECIMALS = 9

        private val INTEGER_TYPES = setOf("int8", "int16", "int32", "int64", "uint8", "uint16", "uint32", "uint64")
    }
}
