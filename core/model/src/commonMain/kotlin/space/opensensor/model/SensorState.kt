package space.opensensor.model

/**
 * What a status column says now, read through the gate of a column it guards. A status column is the
 * column a measurement's `opensensor:valid_when` compares by `equals`, a sensor's own status code.
 * Its `classification:classes` give the meaning of [value].
 *
 * [valid] is the gate's own test, true for the class whose value the gate's `equals` names. [cls] is
 * the class of [value], or null when the status column lists no classes or none for [value]. A family
 * keys its own words on [CodeClass.name] and falls back to [label] for a name it does not know.
 */
data class SensorState(val column: String, val value: Long, val cls: CodeClass?, val valid: Boolean) {
    /** True for the class the template marks `nodata`, no sensor answering. */
    val absent: Boolean get() = cls?.nodata == true

    /** The class title, else its name with `_` as spaces, or null without a class. */
    val label: String? get() = cls?.let { it.title ?: it.name.replace('_', ' ') }

    companion object {
        /**
         * The state of the status column [gate] reads, now [raw]. Null for no reading, a value that is
         * not a whole number, or a gate that is not an `equals` test of another column.
         */
        fun of(gate: ValidityGate?, status: Datastream?, raw: Double?): SensorState? {
            if (gate !is ValidityGate.Equals || raw == null || !raw.isFinite() || raw != kotlin.math.floor(raw)) return null
            val value = raw.toLong()
            return SensorState(gate.column, value, status?.classOf(value), gate.accepts(value))
        }
    }
}

/**
 * The gate of [column] when it is a status gate, an `equals` test of another column. Null for an
 * ungated column, a `finite` gate or a column this thing does not have.
 */
fun Thing.stateGate(column: String): ValidityGate.Equals? =
    (datastream(column)?.datastream?.validWhen as? ValidityGate.Equals)?.takeIf { it.column != column }
