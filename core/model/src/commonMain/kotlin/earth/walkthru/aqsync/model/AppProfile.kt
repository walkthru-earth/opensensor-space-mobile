package earth.walkthru.aqsync.model

import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.max

/**
 * The app's presentation choices for one firmware dictionary, keyed by [dict]. App owned, bundled and
 * never published. A dictionary without a bundled profile gets one [derived] from its template.
 */
data class AppProfile(
    val dict: String,
    val family: Family,
    /** Ordered default columns for Today, History and Devices. The first is the primary reading. */
    val primary: List<String>,
    /** Optional hero card id that a family plugin renders. */
    val hero: String? = null,
    /** Display step per column, such as `1` for whole counts or `0.1`. See [displayStepOf]. */
    val displayStep: Map<String, Double> = emptyMap(),
    /** Column labels by language. */
    val labels: Map<String, LocalizedText> = emptyMap(),
    /** True when [derive] built it from a template, false for a bundled profile. */
    val isDerived: Boolean = false,
) {
    init {
        require(displayStep.values.all { it > 0 && it.isFinite() }) { "profile $dict has a display step that is not positive" }
    }

    /** The step [datastream] shows in, from the profile, else `1` for an integer type and `0.1` otherwise. */
    fun displayStepOf(datastream: Datastream): Double =
        displayStep[datastream.name] ?: if (datastream.isInteger) 1.0 else DEFAULT_DECIMAL_STEP

    companion object {
        const val DEFAULT_DECIMAL_STEP = 0.1

        /**
         * Parses the app's profile JSON. Unknown fields are ignored.
         *
         * @throws IllegalArgumentException when the JSON is not a valid app profile.
         */
        fun parse(json: String): AppProfile = ModelJson.parseProfile(json)

        /**
         * The default profile of [template], so an unknown dictionary still renders. Its primary
         * reading is the first `physicalMeasurement` column, and labels are [titles] from the station
         * Collection, else column names.
         */
        fun derive(template: BoardTemplate, titles: Map<String, String> = emptyMap()): AppProfile = AppProfile(
            dict = template.dict,
            family = Family.GENERIC,
            primary = listOfNotNull(template.datastreams.firstOrNull { it.role == Role.PHYSICAL_MEASUREMENT }?.name),
            labels = template.datastreams.associate { it.name to LocalizedText.of(titles[it.name] ?: it.name) },
            isDerived = true,
        )

        /** Decimals a value shows with at [step]: 0 for `1` or more, 1 for `0.1`, 2 for `0.01` or `0.05`. */
        fun decimalsOf(step: Double): Int = max(0, ceil(-log10(step) - 1e-9).toInt())
    }
}
