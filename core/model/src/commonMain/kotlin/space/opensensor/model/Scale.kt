package space.opensensor.model

import kotlinx.serialization.json.JsonElement

/**
 * One step of a [Scale]. A value belongs to the first step whose [upper] it does not exceed. A null
 * [upper] is open ended. [colorHint] is `RRGGBB` hex.
 */
data class ScaleStep(val upper: Double?, val label: LocalizedText, val colorHint: String? = null)

/**
 * A published scale from `opensensor:scales` of a board template, a thing Collection or the
 * [ScaleRegistry], keyed by [id]. [source] is the citation URL and [guideline] an optional reference
 * line. [period] is the ISO 8601 averaging period the bounds apply to, such as `P1D`, so a client that
 * compares a shorter or longer mean says which period it shows. The stac-opensensor profile requires
 * it, a feed scale may still leave it out.
 */
data class Scale(
    val id: String,
    val source: String,
    val steps: List<ScaleStep>,
    val unit: String? = null,
    val guideline: Double? = null,
    val period: String? = null,
) {
    init {
        require(steps.isNotEmpty()) { "scale $id has no steps" }
        require(period == null || ISO_PERIOD.matches(period)) { "scale $id has the period $period, not an ISO 8601 duration" }
        // stepIndexOf takes the first step that fits, so a later step must stay reachable.
        require(steps.dropLast(1).none { it.upper == null }) { "scale $id has an open step before the last one" }
        val bounds = steps.mapNotNull { it.upper }
        require(bounds.all { it.isFinite() }) { "scale $id has a bound that is not finite" }
        require(bounds.zipWithNext().all { (a, b) -> a < b }) { "scale $id has bounds that do not rise" }
    }

    /**
     * The index of [value]'s step, or -1 for a non-finite value or a value above a closed last step.
     * Steps match inclusive of their upper bound.
     */
    fun stepIndexOf(value: Double): Int {
        if (!value.isFinite()) return -1
        return steps.indexOfFirst { it.upper == null || value <= it.upper }
    }

    /** The step of [value], or null when [stepIndexOf] finds none. */
    fun stepOf(value: Double): ScaleStep? = steps.getOrNull(stepIndexOf(value))

    companion object {
        /** The stac-opensensor `period` pattern, an ISO 8601 duration with at least one part. */
        private val ISO_PERIOD = Regex("^P(?!$)([0-9]+Y)?([0-9]+M)?([0-9]+W)?([0-9]+D)?(T(?=[0-9])([0-9]+H)?([0-9]+M)?([0-9]+S)?)?$")

        /**
         * One entry of `opensensor:scales`, already parsed as JSON, keyed by [id].
         *
         * @throws IllegalArgumentException when [element] is not a scale.
         */
        fun parse(id: String, element: JsonElement): Scale = ModelJson.parseScale(id, element)
    }
}
