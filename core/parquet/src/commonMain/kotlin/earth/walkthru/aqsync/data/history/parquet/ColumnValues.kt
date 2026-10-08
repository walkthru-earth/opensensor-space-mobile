package earth.walkthru.aqsync.data.history.parquet

/**
 * One decoded column: a primitive array plus an optional validity mask, one slot per row (nulls keep
 * their slot so every column of a file lines up by index). No boxing anywhere — this is what the
 * charts bin and what the derive step aggregates.
 *
 * [Binary] (BYTE_ARRAY: WKB geometries, UTF-8 strings) is Arrow-style: one contiguous byte buffer and
 * `size + 1` offsets; a null row has a zero-length slice.
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

    class Float64(val values: DoubleArray, override val valid: BooleanArray?) : ColumnValues() {
        override val size: Int get() = values.size
    }

    class Binary(
        /** `size + 1` entries: value `i` is `data[offsets[i] until offsets[i + 1]]`. */
        val offsets: IntArray,
        val data: ByteArray,
        override val valid: BooleanArray?,
    ) : ColumnValues() {
        init {
            require(offsets.isNotEmpty() && offsets.last() <= data.size) { "offsets do not fit the data" }
        }

        override val size: Int get() = offsets.size - 1

        fun length(i: Int): Int = offsets[i + 1] - offsets[i]

        /** A copy of value [i], or null. */
        fun bytes(i: Int): ByteArray? = if (isNull(i)) null else data.copyOfRange(offsets[i], offsets[i + 1])

        /** Value [i] decoded as UTF-8, or null. */
        fun string(i: Int): String? = if (isNull(i)) null else data.decodeToString(offsets[i], offsets[i + 1])
    }

    /**
     * Every row as a `LongArray`, no boxing: INT64 as stored (the same array, not a copy), INT32 widened.
     * Null slots hold 0, so check [valid]. Throws [ParquetFormatException] for floating or binary columns.
     */
    fun longValues(): LongArray = when (this) {
        is Int64 -> values
        is Int32 -> LongArray(size) { values[it].toLong() }
        else -> throw ParquetFormatException("${this::class.simpleName} column has no exact Long values")
    }

    /**
     * Every row as a `DoubleArray`, no boxing: DOUBLE as stored (the same array), the other numeric types
     * widened into a new array. Null slots are NaN in the widened forms; in a stored DOUBLE they hold
     * whatever the file left there, so check [valid]. Throws [ParquetFormatException] for binary columns.
     */
    fun doubleValues(): DoubleArray = when (this) {
        is Float64 -> values
        is Float32 -> DoubleArray(size) { if (isNull(it)) Double.NaN else values[it].toDouble() }
        is Int32 -> DoubleArray(size) { if (isNull(it)) Double.NaN else values[it].toDouble() }
        is Int64 -> DoubleArray(size) { if (isNull(it)) Double.NaN else values[it].toDouble() }
        is Binary -> throw ParquetFormatException("BYTE_ARRAY column has no numeric values")
    }

    /** Value at [i] widened to a Double, or null. Convenience for generic code (tests, "all 77 columns" tables); hot paths use the arrays. */
    fun asDouble(i: Int): Double? = when {
        isNull(i) -> null
        this is Int32 -> values[i].toDouble()
        this is Int64 -> values[i].toDouble()
        this is Float32 -> values[i].toDouble()
        this is Float64 -> values[i]
        else -> null
    }

    /** Value at [i] as a Long (exact for the integer types, truncating for floats), or null. */
    fun asLong(i: Int): Long? = when {
        isNull(i) -> null
        this is Int32 -> values[i].toLong()
        this is Int64 -> values[i]
        this is Float32 -> values[i].toLong()
        this is Float64 -> values[i].toLong()
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
                    if (v == null) valid.fill(true, offset, offset + p.size) else v.copyInto(valid, offset, 0, p.size)
                    offset += p.size
                }
            }
            offset = 0
            return when (val first = parts[0]) {
                is Int32 -> {
                    val out = IntArray(total)
                    for (p in parts) {
                        (p as? Int32 ?: mismatch(first, p)).values.copyInto(out, offset, 0, p.size)
                        offset += p.size
                    }
                    Int32(out, valid)
                }
                is Int64 -> {
                    val out = LongArray(total)
                    for (p in parts) {
                        (p as? Int64 ?: mismatch(first, p)).values.copyInto(out, offset, 0, p.size)
                        offset += p.size
                    }
                    Int64(out, valid)
                }
                is Float32 -> {
                    val out = FloatArray(total)
                    for (p in parts) {
                        (p as? Float32 ?: mismatch(first, p)).values.copyInto(out, offset, 0, p.size)
                        offset += p.size
                    }
                    Float32(out, valid)
                }
                is Float64 -> {
                    val out = DoubleArray(total)
                    for (p in parts) {
                        (p as? Float64 ?: mismatch(first, p)).values.copyInto(out, offset, 0, p.size)
                        offset += p.size
                    }
                    Float64(out, valid)
                }
                is Binary -> {
                    val bins = parts.map { it as? Binary ?: mismatch(first, it) }
                    val bytes = bins.sumOf { it.offsets.last() }
                    val data = ByteArray(bytes)
                    val offsets = IntArray(total + 1)
                    var row = 0
                    var byte = 0
                    for (b in bins) {
                        val len = b.offsets.last()
                        b.data.copyInto(data, byte, 0, len)
                        for (i in 0 until b.size) offsets[row + i] = byte + b.offsets[i]
                        row += b.size
                        byte += len
                    }
                    offsets[total] = byte
                    Binary(offsets, data, valid)
                }
            }
        }

        private fun mismatch(a: ColumnValues, b: ColumnValues): Nothing =
            throw ParquetFormatException("cannot concatenate ${a::class.simpleName} with ${b::class.simpleName}")
    }
}
