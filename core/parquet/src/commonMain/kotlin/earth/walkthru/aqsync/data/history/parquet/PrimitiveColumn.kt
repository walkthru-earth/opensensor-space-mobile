package earth.walkthru.aqsync.data.history.parquet

/**
 * One decoded column of a [ParquetReader.scan]: a primitive array with exactly [size] slots and a
 * validity bitset. Bit `i` of [validity] (`validity[i ushr 6] ushr (i and 63)`) is set when row `i`
 * holds a value; [validity] is null when every row does. Null slots hold 0. Nothing is boxed.
 *
 * [Bin] is Arrow style: value `i` is `data[offsets[i] until offsets[i + 1]]`. [data] may be longer
 * than `offsets[size]`, the decoder does not trim it.
 */
sealed class PrimitiveColumn(val size: Int, val validity: LongArray?) {
    fun isNull(i: Int): Boolean = validity != null && (validity[i ushr 6] ushr (i and 63)) and 1L == 0L

    val nullCount: Int get() = if (validity == null) 0 else size - popCount(validity, size)

    class I32(val values: IntArray, size: Int, validity: LongArray?) : PrimitiveColumn(size, validity)
    class I64(val values: LongArray, size: Int, validity: LongArray?) : PrimitiveColumn(size, validity)
    class F32(val values: FloatArray, size: Int, validity: LongArray?) : PrimitiveColumn(size, validity)
    class F64(val values: DoubleArray, size: Int, validity: LongArray?) : PrimitiveColumn(size, validity)
    class Bin(val offsets: IntArray, val data: ByteArray, size: Int, validity: LongArray?) : PrimitiveColumn(size, validity) {
        fun string(i: Int): String? = if (isNull(i)) null else data.decodeToString(offsets[i], offsets[i + 1])
    }

    /** Value [i] widened to a Double, or null. */
    fun asDouble(i: Int): Double? = if (isNull(i)) {
        null
    } else {
        when (this) {
            is I32 -> values[i].toDouble()
            is I64 -> values[i].toDouble()
            is F32 -> values[i].toDouble()
            is F64 -> values[i]
            is Bin -> null
        }
    }

    /** Value [i] as a Long (exact for integers, truncating for floats), or null. */
    fun asLong(i: Int): Long? = if (isNull(i)) {
        null
    } else {
        when (this) {
            is I32 -> values[i].toLong()
            is I64 -> values[i]
            is F32 -> values[i].toLong()
            is F64 -> values[i].toLong()
            is Bin -> null
        }
    }

    /**
     * The same rows as a [ColumnValues] for callers that have not moved yet. The value arrays are shared
     * when they hold exactly [size] slots; the validity becomes a `BooleanArray` only when a row is null.
     */
    fun toColumnValues(): ColumnValues {
        val valid = validity?.let { bits -> BooleanArray(size) { (bits[it ushr 6] ushr (it and 63)) and 1L != 0L } }
        return when (this) {
            is I32 -> ColumnValues.Int32(if (values.size == size) values else values.copyOf(size), valid)
            is I64 -> ColumnValues.Int64(if (values.size == size) values else values.copyOf(size), valid)
            is F32 -> ColumnValues.Float32(if (values.size == size) values else values.copyOf(size), valid)
            is F64 -> ColumnValues.Float64(if (values.size == size) values else values.copyOf(size), valid)
            is Bin -> ColumnValues.Binary(if (offsets.size == size + 1) offsets else offsets.copyOf(size + 1), data, valid)
        }
    }

    internal companion object {
        fun words(rows: Int): Int = (rows + 63) ushr 6

        fun popCount(bits: LongArray, rows: Int): Int {
            var n = 0
            for (w in 0 until words(rows)) n += bits[w].countOneBits()
            return n
        }
    }
}

/**
 * The result of a [ParquetReader.scan]: the selected rows of every requested column, aligned by index.
 * Columns the file does not have are absent from [columns]. [rowGroupsRead] and [pagesRead] say how much
 * was decoded, [rowGroupsTotal] and [pagesTotal] how much the file holds for the requested columns.
 */
class ColumnBatch(
    val rowCount: Int,
    val columns: Map<String, PrimitiveColumn>,
    val rowGroupsRead: Int,
    val rowGroupsTotal: Int,
    val pagesRead: Int,
    val pagesTotal: Int,
) {
    operator fun get(name: String): PrimitiveColumn? = columns[name]
}
