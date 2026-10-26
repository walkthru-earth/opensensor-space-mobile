package space.opensensor.model

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.round

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
    /** Unit text per column, such as `µg/m³` for `ug/m3`. A column without one shows its template unit. */
    val displayUnit: Map<String, String> = emptyMap(),
    /** Health scale id per column, such as `pm25`, the property id of a published scale. */
    val scales: Map<String, String> = emptyMap(),
    /** Columns the board writes but never fills, such as the CoreS3 ambient columns. Never shown, not even in LIVE. */
    val hidden: Set<String> = emptySet(),
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
         * [profile] when it is for [template]'s dictionary and names one of its columns as primary,
         * else [derive] of [template], so an unknown digest still renders.
         */
        fun select(template: BoardTemplate, profile: AppProfile?, titles: Map<String, String> = emptyMap()): AppProfile =
            profile?.takeIf { p -> p.dict == template.dict && p.primary.any { template.datastream(it) != null } }
                ?: derive(template, titles)

        /**
         * The default profile of [template], so an unknown dictionary still renders. Its primary
         * reading is the first `physicalMeasurement` column, and labels are [titles] from the thing
         * Collection, else column names. Units stay the template's own and no column gets a scale, so
         * nothing is guessed.
         */
        fun derive(template: BoardTemplate, titles: Map<String, String> = emptyMap()): AppProfile = AppProfile(
            dict = template.dict,
            family = Family.GENERIC,
            primary = listOfNotNull(template.datastreams.firstOrNull { it.role == Role.PHYSICAL_MEASUREMENT }?.name),
            labels = template.datastreams.associate { it.name to LocalizedText.of(titles[it.name] ?: it.name) },
            isDerived = true,
        )

        /**
         * Decimals a value shows with at [step], the fewest that write [step] exactly. 0 for `1`, `10`
         * or `2`, 1 for `0.1` or `0.5`, 2 for `0.01`, `0.05` or `0.25`.
         */
        fun decimalsOf(step: Double): Int {
            var scaled = step
            for (decimals in 0 until MAX_DECIMALS) {
                if (abs(scaled - round(scaled)) <= STEP_TOLERANCE * max(1.0, scaled)) return decimals
                scaled *= 10
            }
            return MAX_DECIMALS
        }

        private const val MAX_DECIMALS = 9
        private const val STEP_TOLERANCE = 1e-9
    }
}
