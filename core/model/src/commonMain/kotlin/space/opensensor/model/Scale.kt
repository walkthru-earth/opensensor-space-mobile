package space.opensensor.model

import kotlinx.serialization.json.JsonElement

/**
 * One step of a [Scale]. A value belongs to the first step whose [upper] it does not exceed. A null
 * [upper] is open ended. [colorHint] is `RRGGBB` hex.
 */
data class ScaleStep(val upper: Double?, val label: LocalizedText, val colorHint: String? = null)

/**
 * A published scale from the station Collection's `opensensor:scales`, keyed by [id]. [source] is the
 * citation URL and [guideline] an optional reference line.
 */
data class Scale(
    val id: String,
    val source: String,
    val steps: List<ScaleStep>,
    val unit: String? = null,
    val guideline: Double? = null,
) {
    init {
        require(steps.isNotEmpty()) { "scale $id has no steps" }
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
        /**
         * One entry of `opensensor:scales`, already parsed as JSON, keyed by [id].
         *
         * @throws IllegalArgumentException when [element] is not a scale.
         */
        fun parse(id: String, element: JsonElement): Scale = ModelJson.parseScale(id, element)
    }
}
