package space.opensensor.model

/**
 * A published health scale for one property, read against fixed ascending edges in the property's
 * own unit. A value sits in one of `edges.size + 1` steps, step 0 at or below the first edge (the
 * guideline) and the last step above the loosest edge. Edges are inclusive from below. A family
 * names its scales ([DerivedMetricProvider.scales]) and the profile's `scales` id picks one, so a
 * property without one stays neutral. Colours and step names belong to the family that publishes it.
 */
data class HealthScale(
    /** The property id this scale reads, the same id the metric registry and the public feed use. */
    val propertyId: String,
    /** The unit the edges are in. The scale never converts. */
    val unit: String,
    /** Upper edges of every closed step, ascending, the guideline first. */
    val edges: List<Double>,
    /** The publication, for the legend's info sheet and the docs. */
    val source: String,
) {
    init {
        require(edges.isNotEmpty()) { "a health scale has at least the guideline edge" }
        require(edges.zipWithNext().all { (a, b) -> a < b }) { "edges ascend" }
    }

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

    /** The value range of step [index], lower bound exclusive (null below the first step), upper inclusive (null above the last). */
    fun rangeOf(index: Int): Pair<Double?, Double?> = edges.getOrNull(index - 1) to edges.getOrNull(index)
}
