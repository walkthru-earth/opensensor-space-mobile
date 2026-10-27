package space.opensensor.model

/**
 * The phone derived datastreams of one device family, such as the air family's EPA corrected PM2.5.
 * A provider names each one as data, a [Datastream] with [Datastream.derived] set, plus its per row
 * [RowFormula], so History builds it like a stored column and holds no family code. The app lists its
 * providers once where it builds the metric catalog, never from static registration.
 */
interface DerivedMetricProvider {
    /** The datastreams the phone derives for [template], empty when it lacks their source columns. */
    fun derive(template: BoardTemplate): List<DerivedMetric>
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
