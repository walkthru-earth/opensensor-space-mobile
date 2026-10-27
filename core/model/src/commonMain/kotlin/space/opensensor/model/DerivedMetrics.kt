package space.opensensor.model

/**
 * What one device family (`:family:*`) adds to the generic core. Its phone derived datastreams, each
 * named as data, a [Datastream] with [Datastream.derived] set, plus its per row [RowFormula], so
 * History builds it like a stored column and holds no family code. Its health [scales], which a
 * profile's `scales` id picks. And the [capabilitiesOf] a Thing of the family. The app lists its
 * providers once where it builds the metric catalog, never from static registration.
 */
fun interface DerivedMetricProvider {
    /** The datastreams the phone derives for [template], empty when it lacks their source columns. */
    fun derive(template: BoardTemplate): List<DerivedMetric>

    /** The health scales this family publishes, keyed by the scale id a profile's `scales` names. */
    val scales: Map<String, HealthScale> get() = emptyMap()

    /** The family capabilities [thing] has, beyond the generic ones core reads from any template. */
    fun capabilitiesOf(thing: Thing): Set<Capability> = emptySet()
}

/** One row's value of a derived datastream from its source columns. */
fun interface RowFormula {
    /**
     * The value from the row's [primary] value, the first of `derived.from`, and its [second] value,
     * the second source column or null when there is none or the row has no value. Null when the row
     * has no value.
     */
    fun valueOf(primary: Double, second: Double?): Double?
}

/**
 * A derived [datastream] with its English [label] and [formula]. Its `derived.from` lists one or two
 * stored columns, the ones [RowFormula.valueOf] reads in order. [viewOf] is the stored column this is a
 * corrected view of, whose profile unit, scale and primary flag it shares, or null for a new quantity.
 */
class DerivedMetric(
    val datastream: Datastream,
    val label: String,
    val formula: RowFormula,
    val viewOf: String? = null,
) {
    init {
        val from = requireNotNull(datastream.derived) { "derived metric ${datastream.name} has no derivation" }.from
        require(from.size <= 2) { "derived metric ${datastream.name} reads more than two columns" }
    }
}

/**
 * Which of two views of one quantity a value is. A stored column with a derived view ([DerivedMetric.viewOf])
 * is [RAW] and that view is [CORRECTED], whatever the family, so every chart and card can say which it shows.
 */
enum class Basis {
    /** The column as the board stored it. */
    RAW,

    /** The phone's per row correction of the stored column. */
    CORRECTED,
}
