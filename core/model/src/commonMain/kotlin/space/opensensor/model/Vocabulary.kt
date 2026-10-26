package space.opensensor.model

import kotlin.jvm.JvmInline

/**
 * The one ACDD `coverage_content_type` value in a column's core `roles`. Open, so a role this build
 * does not know still parses and keeps its id.
 */
@JvmInline
value class Role(val id: String) {
    /** True for a reading of the world, measured or modelled, the columns a screen charts by default. */
    val isMeasurement: Boolean get() = this == PHYSICAL_MEASUREMENT || this == MODEL_RESULT

    companion object {
        val PHYSICAL_MEASUREMENT = Role("physicalMeasurement")
        val AUXILIARY_INFORMATION = Role("auxiliaryInformation")
        val QUALITY_INFORMATION = Role("qualityInformation")
        val MODEL_RESULT = Role("modelResult")
        val REFERENCE_INFORMATION = Role("referenceInformation")
        val COORDINATE = Role("coordinate")
    }
}

/**
 * How tiers roll a column up, in CF `cell_methods` words (`opensensor:aggregate`). The set is open,
 * so a template from a newer firmware with an aggregate this build does not know still parses. Such
 * an aggregate keeps its [id] and rolls up as [POINT], see [rollup].
 */
@JvmInline
value class Aggregate(val id: String) {
    /** True when this build knows how to apply the aggregate. */
    val isKnown: Boolean get() = this in KNOWN

    /** The aggregate the tiers apply, [POINT] for one this build does not know. */
    val rollup: Aggregate get() = if (isKnown) this else POINT

    companion object {
        val MEAN = Aggregate("mean")
        val MINIMUM = Aggregate("minimum")
        val MAXIMUM = Aggregate("maximum")
        val SUM = Aggregate("sum")
        val POINT = Aggregate("point")
        val MODE = Aggregate("mode")

        /** The aggregates of stac-opensensor v0.1.0. */
        val KNOWN: Set<Aggregate> = setOf(MEAN, MINIMUM, MAXIMUM, SUM, POINT, MODE)
    }
}

/**
 * The device family an app profile routes to (`air`, `weather`, `soil` and so on), which picks a
 * family plugin. Open, a family with no plugin renders with generic cards.
 */
@JvmInline
value class Family(val id: String) {
    companion object {
        /** The family of a profile derived from a template, no plugin. */
        val GENERIC = Family("generic")

        /** Air quality, the particle family plugin. */
        val AIR = Family("air")
    }
}

/** A value a [ValidityGate.Equals] compares with, a number, a string or a boolean. */
sealed interface GateValue {
    data class Number(val value: Double) : GateValue
    data class Text(val value: String) : GateValue
    data class Flag(val value: Boolean) : GateValue
}

/**
 * A row's value counts only when its gate passes (`opensensor:valid_when`). A failing gate is an
 * absent reading, not a zero. The caller reads [column] from the row and passes it to [accepts].
 */
sealed interface ValidityGate {
    val column: String

    /** True when [gateValue], the row's value of [column], passes. Null never passes. */
    fun accepts(gateValue: Any?): Boolean

    /** Passes when [column] equals [value]. Numbers compare by value, so `4`, `4L` and `4.0` match `4`. */
    data class Equals(override val column: String, val value: GateValue) : ValidityGate {
        override fun accepts(gateValue: Any?): Boolean = when (value) {
            is GateValue.Number -> gateValue is Number && gateValue.toDouble() == value.value
            is GateValue.Text -> gateValue == value.value
            is GateValue.Flag -> gateValue == value.value
        }
    }

    /** Passes when [column] holds a finite number. */
    data class Finite(override val column: String) : ValidityGate {
        override fun accepts(gateValue: Any?): Boolean = gateValue is Number && gateValue.toDouble().isFinite()
    }
}

/** `opensensor:plausible_range`. Values outside are suspect, not masked. */
data class PlausibleRange(val min: Double, val max: Double) {
    init {
        require(min <= max) { "plausible range min $min is above max $max" }
    }

    operator fun contains(value: Double): Boolean = value in min..max
}

/**
 * `opensensor:derived`. The datastream is computed on the phone by [method] from the [from] columns,
 * so its id is not a stored column name.
 */
data class Derivation(val from: List<String>, val method: String) {
    init {
        require(from.isNotEmpty()) { "a derived datastream reads at least one column" }
    }
}

/** One `classification:classes` entry, the meaning of a code value. [nodata] marks the missing value. */
data class CodeClass(val value: Long, val name: String, val colorHint: String? = null, val nodata: Boolean = false)

/**
 * A label as a plain string or a map from language code to string. A plain string is kept under
 * [UNTAGGED].
 */
data class LocalizedText(val byLanguage: Map<String, String>) {
    init {
        require(byLanguage.isNotEmpty()) { "a label has at least one text" }
    }

    /** The text in [language], else English, else the plain string, else the first one. */
    fun get(language: String): String =
        byLanguage[language] ?: byLanguage[ENGLISH] ?: byLanguage[UNTAGGED] ?: byLanguage.values.first()

    companion object {
        const val UNTAGGED = ""
        const val ENGLISH = "en"

        fun of(text: String): LocalizedText = LocalizedText(mapOf(UNTAGGED to text))
    }
}
