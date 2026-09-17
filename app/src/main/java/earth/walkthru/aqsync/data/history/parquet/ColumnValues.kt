package earth.walkthru.aqsync.data.history.parquet

/**
 * One decoded column: a primitive array plus an optional validity mask, one slot per row (nulls keep
 * their slot so every column of a file lines up by index). No boxing anywhere — this is what the
 * charts bin and what the derive step aggregates.
 */
sealed class ColumnValues {
    abstract val size: Int

    /** `null` when every value is defined (REQUIRED column, or an OPTIONAL one without nulls). */
    abstract val valid: BooleanArray?

    fun isNull(i: Int): Boolean = valid?.let { !it[i] } ?: false

    val nullCount: Int get() = valid?.count { !it } ?: 0

    class Int32(val values: IntArray, override val valid: BooleanArray?) : ColumnValues() {
        override val size: Int get() = values.size
    }

    class Int64(val values: LongArray, override val valid: BooleanArray?) : ColumnValues() {
        override val size: Int get() = values.size
    }

    class Float32(val values: FloatArray, override val valid: BooleanArray?) : ColumnValues() {
        override val size: Int get() = values.size
    }

    /** Value at [i] widened to a Double, or null. Convenience for generic code (tests, "all 77 columns" tables); hot paths use the arrays. */
    fun asDouble(i: Int): Double? = when {
        isNull(i) -> null
        this is Int32 -> values[i].toDouble()
        this is Int64 -> values[i].toDouble()
        this is Float32 -> values[i].toDouble()
        else -> null
    }

    /** Value at [i] as a Long (exact for the integer types, truncating for floats), or null. */
    fun asLong(i: Int): Long? = when {
        isNull(i) -> null
        this is Int32 -> values[i].toLong()
        this is Int64 -> values[i]
        this is Float32 -> values[i].toLong()
        else -> null
    }

    /** Concatenates same-typed columns (row groups of one file, or files of one day) in order. */
    companion object {
        fun concat(parts: List<ColumnValues>): ColumnValues {
            require(parts.isNotEmpty()) { "nothing to concatenate" }
            if (parts.size == 1) return parts[0]
            val total = parts.sumOf { it.size }
            val anyNulls = parts.any { it.valid != null }
            val valid = if (anyNulls) BooleanArray(total) else null
            var offset = 0
            if (valid != null) {
                for (p in parts) {
                    val v = p.valid
                    if (v == null) java.util.Arrays.fill(valid, offset, offset + p.size, true) else System.arraycopy(v, 0, valid, offset, p.size)
                    offset += p.size
                }
            }
            offset = 0
            return when (val first = parts[0]) {
                is Int32 -> {
                    val out = IntArray(total)
                    for (p in parts) {
                        System.arraycopy((p as? Int32 ?: mismatch(first, p)).values, 0, out, offset, p.size)
                        offset += p.size
                    }
                    Int32(out, valid)
                }
                is Int64 -> {
                    val out = LongArray(total)
                    for (p in parts) {
                        System.arraycopy((p as? Int64 ?: mismatch(first, p)).values, 0, out, offset, p.size)
                        offset += p.size
                    }
                    Int64(out, valid)
                }
                is Float32 -> {
                    val out = FloatArray(total)
                    for (p in parts) {
                        System.arraycopy((p as? Float32 ?: mismatch(first, p)).values, 0, out, offset, p.size)
                        offset += p.size
                    }
                    Float32(out, valid)
                }
            }
        }

        private fun mismatch(a: ColumnValues, b: ColumnValues): Nothing =
            throw ParquetFormatException("cannot concatenate ${a::class.simpleName} with ${b::class.simpleName}")
    }
}
