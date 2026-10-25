package earth.walkthru.aqsync.model

/**
 * The quantity a datastream observes, the same on every board. [id] comes from a shared vocabulary
 * (NERC P07, CF) when the template names one, else it is `urn:walkthru-earth:property:<quantity>`. It
 * never names a board, so two boards that measure the same quantity share it. Which id each quantity
 * gets is still open in the spec, so callers should not join on [standardName].
 */
data class ObservedProperty(val id: String, val standardName: String? = null) {
    init {
        require(id.isNotEmpty()) { "an observed property has an empty id" }
        require(!BOARD_SCOPED.containsMatchIn(id)) { "property $id names a board" }
    }

    companion object {
        /** Prefix of a board-neutral property id the app mints when no shared vocabulary has one. */
        const val URN_PREFIX = "urn:walkthru-earth:property:"

        /** A `urn:walkthru-earth:<board>:property:<quantity>` id, as early templates wrote them. */
        private val BOARD_SCOPED = Regex("^urn:walkthru-earth:[^:]+:property:(.+)$")

        /**
         * The property for a template's `opensensor:property` and `cf:standard_name`, or null when it
         * has neither. A board-scoped walkthru URN is rewritten to its board-neutral form.
         */
        fun of(property: String?, standardName: String?): ObservedProperty? {
            val id = property?.takeIf { it.isNotEmpty() }?.let { raw ->
                BOARD_SCOPED.find(raw)?.let { URN_PREFIX + it.groupValues[1] } ?: raw
            } ?: return null
            return ObservedProperty(id, standardName)
        }
    }
}

/**
 * The instrument that produces a datastream. [procedure] is the template's `opensensor:procedure`,
 * such as `pmsa003`. [board] is the board template id that carries it, since the same sensor model
 * on two boards can be wired and calibrated differently.
 */
data class Sensor(val procedure: String, val board: String? = null) {
    init {
        require(procedure.isNotEmpty()) { "a sensor has an empty procedure" }
    }
}

/**
 * One column of a board template, or one datastream the phone derives from columns. It carries only
 * template facts. Titles and scales come from the station Collection and labels and display steps from
 * the app profile, see [StationDatastream].
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
) {
    val isDerived: Boolean get() = derived != null

    /** The stored columns this datastream reads, itself for a stored column. */
    val sourceColumns: List<String> get() = derived?.from ?: listOf(name)

    /** The aggregate the tiers apply. Missing or unknown rolls up as [Aggregate.POINT]. */
    val rollup: Aggregate get() = aggregate?.rollup ?: Aggregate.POINT

    /** True for an integer Parquet type, whose values show without decimals by default. */
    val isInteger: Boolean get() = type in INTEGER_TYPES

    /** The code class of [value], or null for a column without classes or an unlisted value. */
    fun classOf(value: Long): CodeClass? = classes.firstOrNull { it.value == value }

    private companion object {
        val INTEGER_TYPES = setOf("int8", "int16", "int32", "int64", "uint8", "uint16", "uint32", "uint64")
    }
}
