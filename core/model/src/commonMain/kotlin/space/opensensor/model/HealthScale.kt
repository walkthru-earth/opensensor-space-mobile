package space.opensensor.model

import space.opensensor.units.UcumDisplay

/**
 * A published scale a metric is judged on, read against fixed ascending edges in the column's own
 * unit. A value sits in one of `edges.size + 1` steps, step 0 at or below the first edge (the
 * guideline) and the last step above the loosest edge. Edges are inclusive from below. It is built
 * from a [Scale] entry ([of]), so a column without a scale stays neutral. [steps] keep the entry's
 * labels ([names]) and colour hints, so a screen shows the publisher's words and keeps no copy of
 * them.
 */
data class HealthScale(
    /** The scale id, such as a registry `scales.json` key or a template `opensensor:scales` key. */
    val id: String,
    /** The unit the edges are in, as screens show it. The scale never converts. */
    val unit: String,
    /** Upper edges of every closed step, ascending, the guideline first. */
    val edges: List<Double>,
    /** The publication, for the legend's info sheet and the docs. */
    val source: String,
    /** The steps of the scale entry, one per edge and one open step, or empty when built by hand. */
    val steps: List<ScaleStep> = emptyList(),
    /** The ISO 8601 averaging period the edges apply to, such as `P1D`, or null when the entry gives none. */
    val period: String? = null,
) {
    init {
        require(edges.isNotEmpty()) { "a health scale has at least the guideline edge" }
        require(edges.zipWithNext().all { (a, b) -> a < b }) { "edges ascend" }
        require(steps.isEmpty() || steps.size == edges.size + 1) { "a health scale has one step per edge and one open step" }
    }

    /** The name of each step by language, cleanest first, or empty for a scale built without steps. */
    val names: List<LocalizedText> get() = steps.map { it.label }

    /** The number of steps, one more than the edges since the last step is open. */
    val size: Int get() = edges.size + 1

    /** The guideline value itself, the one line every chart draws. */
    val guideline: Double get() = edges.first()

    /** The middle edge, the lowest top a chart axis takes, so a normal day keeps the guideline readable. */
    val axisFloor: Double get() = edges[edges.size / 2]

    /** The step index [value] falls in, from 0 to [edges] size. Non-finite values have no step. */
    fun stepIndexOf(value: Double): Int? {
        if (!value.isFinite()) return null
        val i = edges.indexOfFirst { value <= it }
        return if (i < 0) edges.size else i
    }

    /** The name of the step [value] falls in, in [language], or null for a non-finite value or a scale without names. */
    fun stepName(value: Double, language: String): String? = stepIndexOf(value)?.let { names.getOrNull(it) }?.get(language)

    /** The value range of step [index], lower bound exclusive (null below the first step), upper inclusive (null above the last). */
    fun rangeOf(index: Int): Pair<Double?, Double?> = edges.getOrNull(index - 1) to edges.getOrNull(index)

    companion object {
        /**
         * The health scale of [scale], or null when it cannot judge a value, with fewer than two steps
         * or a closed last step. [unit] is the shown unit of its edges, by default the scale's UCUM unit
         * as [UcumDisplay] writes it.
         */
        fun of(scale: Scale, unit: String = UcumDisplay.of(scale.unit.orEmpty())): HealthScale? {
            if (scale.steps.size < 2 || scale.steps.last().upper != null) return null
            return HealthScale(scale.id, unit, scale.steps.mapNotNull { it.upper }, scale.source, scale.steps, scale.period)
        }
    }
}
