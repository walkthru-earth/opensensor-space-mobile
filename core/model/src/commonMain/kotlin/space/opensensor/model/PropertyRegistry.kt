package space.opensensor.model

/**
 * The stac-opensensor property registry, `registry/properties.json`, one [PropertyEntry] per
 * `opensensor:property` URI. A client labels, rounds, groups and judges a column of a board it has
 * never seen from this and the template alone. The app bundles a verbatim copy, see
 * [BundledCatalog.properties].
 */
class PropertyRegistry(entries: List<PropertyEntry>) {
    private val byUri: Map<String, PropertyEntry> = entries.associateBy { it.uri }

    init {
        require(byUri.size == entries.size) { "the property registry lists a URI twice" }
    }

    /** Every entry, in registry order. */
    val entries: List<PropertyEntry> = entries.toList()

    /** The entry for [uri], matched exactly, or null for a property the registry does not list. */
    operator fun get(uri: String): PropertyEntry? = byUri[uri]

    /** The entry for [property], or null when there is none or the registry does not list it. */
    fun of(property: ObservedProperty?): PropertyEntry? = property?.let { byUri[it.id] }

    companion object {
        /**
         * Parses a property registry.
         *
         * @throws IllegalArgumentException when [json] is not an `OpenSensorPropertyRegistry`.
         */
        fun parse(json: String): PropertyRegistry = ModelJson.parsePropertyRegistry(json)
    }
}

/**
 * One property registry entry. [label], [shortLabel] and [definition] are keyed by language, English
 * always present. [unit] is the UCUM unit columns of this property carry and [decimals] the default
 * digits after the point. [scale] is the default health scale, see [RegistryScale].
 */
data class PropertyEntry(
    val uri: String,
    val label: LocalizedText,
    val definition: LocalizedText,
    val group: DisplayGroup,
    val decimals: Int,
    val shortLabel: LocalizedText? = null,
    val symbol: String? = null,
    val unit: String? = null,
    val scale: RegistryScale = RegistryScale.Unset,
    val note: String? = null,
) {
    init {
        require(uri.isNotEmpty()) { "a registry entry has an empty URI" }
        require(decimals in 0..Datastream.MAX_DECIMALS) { "registry entry $uri shows $decimals decimals" }
        require(LocalizedText.ENGLISH in label.byLanguage) { "registry entry $uri has no English label" }
    }

    /** The id of the default scale, or null when the entry names none, on purpose or not. */
    val scaleId: String? get() = (scale as? RegistryScale.Named)?.id
}

/**
 * The registry `scale` of a property. [Named] points at a [ScaleRegistry] id. [None] is an explicit
 * `null`, no scale on purpose. [Unset] is a missing key, the registry has no opinion. Either way a
 * client shows no scale unless the column names one.
 */
sealed interface RegistryScale {
    data class Named(val id: String) : RegistryScale {
        init {
            require(id.isNotEmpty()) { "a registry scale id is empty" }
        }
    }

    data object None : RegistryScale

    data object Unset : RegistryScale
}

/**
 * The stac-opensensor scale registry, `registry/scales.json`, the shared [Scale]s by id. The app
 * bundles a verbatim copy, see [BundledCatalog.scales].
 */
class ScaleRegistry(scales: Map<String, Scale>) {
    init {
        scales.forEach { (id, scale) -> require(id == scale.id) { "registry scale $id carries the id ${scale.id}" } }
    }

    /** Every scale by id, in registry order. */
    val scales: Map<String, Scale> = scales.toMap()

    /** The scale [id], or null for an id the registry does not list. A null [id] is no scale. */
    operator fun get(id: String?): Scale? = id?.let { scales[it] }

    companion object {
        /**
         * Parses a scale registry.
         *
         * @throws IllegalArgumentException when [json] is not an `OpenSensorScaleRegistry`.
         */
        fun parse(json: String): ScaleRegistry = ModelJson.parseScaleRegistry(json)
    }
}
