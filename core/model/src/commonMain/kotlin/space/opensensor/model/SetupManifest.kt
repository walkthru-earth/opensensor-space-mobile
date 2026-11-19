package space.opensensor.model

import kotlin.jvm.JvmInline

/**
 * What a board declares about its own setup wizard and Device settings, from the template `firmware`
 * block (opensensor-space-firmware decision 0003). [setup] lists the wizard steps in order, [settings]
 * the Device settings sections in order, and [configKeys] the typed `SET_CONFIG` keys a step or section
 * shows. Find, Pair, Permission and Done stay app owned and are never listed. A key never carries a
 * value, the device answers `GET_CONFIG` for that.
 *
 * Parsing is tolerant. An entry without an id, a key without a type or a value of the wrong JSON shape
 * is dropped, so a bad declaration never costs the template its columns. An unknown step id is kept as
 * data, see [StepId.isKnown]. The app's safety floors, such as never showing a secret key, are applied
 * where keys are sent, not here.
 */
data class SetupManifest(
    val setup: List<ManifestStep> = emptyList(),
    val settings: List<ManifestSection> = emptyList(),
    val configKeys: Map<String, ConfigKeySpec> = emptyMap(),
) {
    /** True when the template declares no step, section or key. */
    val isEmpty: Boolean get() = setup.isEmpty() && settings.isEmpty() && configKeys.isEmpty()

    /** The declared keys of [keys] in order, skipping a name [configKeys] does not declare. */
    fun keysOf(keys: List<String>): List<ConfigKeySpec> = keys.mapNotNull { configKeys[it] }

    companion object {
        /** A template that declares nothing. */
        val NONE: SetupManifest = SetupManifest()
    }
}

/**
 * One `firmware.setup` entry. [required] (`req`) is true when setup cannot finish without the step.
 * [keys] lists the `config_keys` a generic step shows, empty for a catalog step that needs none.
 */
data class ManifestStep(val id: StepId, val required: Boolean = false, val keys: List<String> = emptyList())

/** One `firmware.settings` entry, a Device settings section and the `config_keys` it shows. */
data class ManifestSection(val id: StepId, val keys: List<String> = emptyList())

/**
 * The id of a setup step or settings section. A [KNOWN] id names a component of the app's fixed
 * catalog. Any other id is a board's own, which the app renders as a generic form from its keys.
 */
@JvmInline
value class StepId(val id: String) {
    /** True for a component of the app's fixed catalog. */
    val isKnown: Boolean get() = this in KNOWN

    companion object {
        val NAME = StepId("name")
        val LOCATION = StepId("location")
        val MOBILITY = StepId("mobility")
        val SITE = StepId("site")
        val WIFI = StepId("wifi")
        val PAIRING = StepId("pairing")
        val LAN = StepId("lan")
        val BONDS = StepId("bonds")

        /** The app's step catalog. */
        val KNOWN: Set<StepId> = setOf(NAME, LOCATION, MOBILITY, SITE, WIFI, PAIRING, LAN, BONDS)
    }
}

/**
 * One `firmware.config_keys` entry, keyed by its `SET_CONFIG` [key]. [page] is the `GET_CONFIG` page
 * that reads it back, or null when the template names none. [label] is the plain label (untagged)
 * with the `label_i18n` languages beside it, or null when the template gives none.
 */
data class ConfigKeySpec(
    val key: String,
    val type: ConfigKeyType,
    val values: ConfigValues? = null,
    val applies: ConfigApplies? = null,
    val page: Int? = null,
    val label: LocalizedText? = null,
) {
    init {
        require(key.isNotEmpty()) { "a config key has an empty name" }
    }
}

/** The `SET_CONFIG` value kind of a key. Open, so a kind this build does not know still parses. */
@JvmInline
value class ConfigKeyType(val id: String) {
    val isKnown: Boolean get() = this in KNOWN

    companion object {
        val BOOLEAN = ConfigKeyType("boolean")
        val INTEGER = ConfigKeyType("integer")
        val NUMBER = ConfigKeyType("number")
        val ENUM = ConfigKeyType("enum")
        val TEXT = ConfigKeyType("text")

        val KNOWN: Set<ConfigKeyType> = setOf(BOOLEAN, INTEGER, NUMBER, ENUM, TEXT)
    }
}

/** When a written key takes effect. Open, so a value this build does not know still parses. */
@JvmInline
value class ConfigApplies(val id: String) {
    val isKnown: Boolean get() = this in KNOWN

    companion object {
        val IMMEDIATELY = ConfigApplies("immediately")
        val NEXT_SAMPLE = ConfigApplies("next sample")
        val NEXT_BOOT = ConfigApplies("next boot")

        /** A one shot action, such as cleaning a fan, not a stored setting. */
        val ACTION = ConfigApplies("action")

        val KNOWN: Set<ConfigApplies> = setOf(IMMEDIATELY, NEXT_SAMPLE, NEXT_BOOT, ACTION)
    }
}

/** The `values` of a config key, an enum list or a numeric range. */
sealed interface ConfigValues {
    /** The allowed values of an enum key, as their JSON text. */
    data class Choices(val values: List<String>) : ConfigValues

    /** The bounds of a numeric key, either one open when null. */
    data class Range(val min: Double?, val max: Double?) : ConfigValues {
        init {
            require(min == null || max == null || min <= max) { "a config range runs from $min down to $max" }
        }
    }
}
