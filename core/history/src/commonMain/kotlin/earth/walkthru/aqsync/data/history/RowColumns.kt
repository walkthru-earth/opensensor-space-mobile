package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.data.history.parquet.ColumnValues
import space.opensensor.model.GateValue
import space.opensensor.model.ValidityGate

/**
 * A decoded column as unboxed longs for the per-row loops of the history pass (#63). The integer
 * types are exact, floats truncate and binary columns are all null, as [ColumnValues.asLong] reads them.
 */
internal class LongColumn(val values: LongArray, private val valid: BooleanArray?) {
    val size: Int get() = values.size

    fun has(i: Int): Boolean = valid == null || valid[i]

    companion object {
        fun of(column: ColumnValues): LongColumn = when (column) {
            is ColumnValues.Int64 -> LongColumn(column.values, column.valid)
            is ColumnValues.Int32 -> LongColumn(LongArray(column.size) { column.values[it].toLong() }, column.valid)
            is ColumnValues.Float32 -> LongColumn(LongArray(column.size) { column.values[it].toLong() }, column.valid)
            is ColumnValues.Float64 -> LongColumn(LongArray(column.size) { column.values[it].toLong() }, column.valid)
            is ColumnValues.Binary -> LongColumn(LongArray(column.size), BooleanArray(column.size))
        }
    }
}

/** The value at [i] of an optional column, 0 when the column or the cell is missing (the [RowKey] rule). */
internal fun LongColumn?.at(i: Int): Long = if (this != null && has(i)) values[i] else 0L

/** A decoded numeric column as unboxed doubles, as [ColumnValues.asDouble] reads it. Binary columns are all null. */
internal class DoubleColumn(val values: DoubleArray, private val valid: BooleanArray?) {
    val size: Int get() = values.size

    fun has(i: Int): Boolean = valid == null || valid[i]

    /**
     * This column's rows as [metric] shows them, [MetricSpec.valueOf] of each row and its [rh]. A row
     * whose result is null (no valid RH, say) is a missing cell. With no RH column every row is missing.
     */
    fun correctedBy(metric: MetricSpec, rh: DoubleColumn?): DoubleColumn {
        val out = DoubleArray(size)
        val ok = BooleanArray(size)
        for (i in 0 until size) {
            if (!has(i)) continue
            val v = metric.valueOf(values[i], if (rh != null && rh.has(i)) rh.values[i] else null) ?: continue
            out[i] = v
            ok[i] = true
        }
        return DoubleColumn(out, ok)
    }

    companion object {
        fun of(column: ColumnValues): DoubleColumn = when (column) {
            is ColumnValues.Binary -> DoubleColumn(DoubleArray(column.size), BooleanArray(column.size))
            else -> DoubleColumn(column.doubleValues(), column.valid)
        }
    }
}

/**
 * A [ValidityGate] over one file's decoded [column], read row by row without boxing. A missing column
 * or a null cell never passes. Numbers compare by value, a flag is true when non-zero, text needs a
 * string column.
 */
internal class GateColumn(private val gate: ValidityGate, column: ColumnValues?) {
    private val numbers: DoubleColumn? = column?.takeUnless { it is ColumnValues.Binary }?.let(DoubleColumn::of)
    private val text: ColumnValues.Binary? = column as? ColumnValues.Binary

    fun passes(i: Int): Boolean = when (gate) {
        is ValidityGate.Finite -> numbers != null && numbers.has(i) && numbers.values[i].isFinite()
        is ValidityGate.Equals -> when (val want = gate.value) {
            is GateValue.Number -> numbers != null && numbers.has(i) && numbers.values[i] == want.value
            is GateValue.Flag -> numbers != null && numbers.has(i) && (numbers.values[i] != 0.0) == want.value
            is GateValue.Text -> text != null && text.string(i) == want.value
        }
    }
}
