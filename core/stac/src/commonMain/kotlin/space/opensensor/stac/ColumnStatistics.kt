package space.opensensor.stac

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import space.opensensor.data.history.parquet.ColumnValues
import space.opensensor.data.history.parquet.ParquetFile
import space.opensensor.data.history.parquet.ParquetType
import space.opensensor.model.BoardTemplate
import space.opensensor.model.Datastream
import space.opensensor.model.GateValue
import space.opensensor.model.ValidityGate
import kotlin.math.round

/**
 * The `table:columns` of a File Item, one entry with STAC `statistics` per measurement column of the
 * template, a physical measurement or a model result, that the file holds as a number. A row counts
 * only when it is not null, is finite and passes the column's `valid_when` gate, so a warming
 * particle sensor never drags the mean to 0. A template column the file lacks is left out.
 */
internal object ColumnStatistics {
    private val NUMERIC = setOf(ParquetType.INT32, ParquetType.INT64, ParquetType.FLOAT, ParquetType.DOUBLE)

    fun of(file: ParquetFile, template: BoardTemplate): JsonArray {
        val rows = file.numRows.toInt()
        val gates = HashMap<String, ColumnValues?>()
        val entries = template.datastreams
            .filter { it.role?.isMeasurement == true }
            .mapNotNull { ds ->
                val leaf = file.metadata.leaf(ds.name)?.takeIf { it.type in NUMERIC } ?: return@mapNotNull null
                val gate = ds.validWhen?.let { g -> gates.getOrPut(g.column) { file.metadata.leaf(g.column)?.let(file::readColumn) } }
                entry(ds, file.readColumn(leaf), ds.validWhen, gate, rows)
            }
        return JsonArray(entries)
    }

    private fun entry(ds: Datastream, values: ColumnValues, gate: ValidityGate?, gateValues: ColumnValues?, rows: Int): JsonObject {
        val integer = values is ColumnValues.Int32 || values is ColumnValues.Int64
        var count = 0
        var sum = 0.0
        var lo = Double.POSITIVE_INFINITY
        var hi = Double.NEGATIVE_INFINITY
        for (i in 0 until values.size) {
            val v = values.asDouble(i) ?: continue
            if (!v.isFinite()) continue
            if (gate != null && !passes(gate, gateValues, i)) continue
            count++
            sum += v
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        return buildJsonObject {
            put("name", ds.name)
            put("type", ds.type)
            putJsonObject("statistics") {
                if (count > 0) {
                    if (integer) {
                        put("minimum", lo.toLong())
                        put("maximum", hi.toLong())
                    } else {
                        put("minimum", round4(lo))
                        put("maximum", round4(hi))
                    }
                    put("mean", round4(sum / count))
                }
                put("count", count)
                put("valid_percent", if (rows == 0) 0.0 else round(1000.0 * count / rows) / 10.0)
            }
        }
    }

    /** True when row [i] of the gate column passes [gate]. A missing column or a null cell never passes. */
    private fun passes(gate: ValidityGate, column: ColumnValues?, i: Int): Boolean {
        if (column == null || column.isNull(i)) return false
        if (column is ColumnValues.Binary) return gate.accepts(column.string(i))
        val v = column.asDouble(i) ?: return false
        return when (gate) {
            is ValidityGate.Finite -> v.isFinite()
            is ValidityGate.Equals -> when (val want = gate.value) {
                is GateValue.Number -> v == want.value
                is GateValue.Flag -> (v != 0.0) == want.value
                is GateValue.Text -> false
            }
        }
    }

    private fun round4(v: Double): Double = round(v * 10_000.0) / 10_000.0
}
