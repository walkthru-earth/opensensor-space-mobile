package earth.walkthru.aqsync.data.history.parquet

import earth.walkthru.aqsync.data.history.parquet.ThriftCompactReader.Type
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes the same Parquet subset the firmware writes and [ParquetFile] reads — flat INT32 / INT64 /
 * FLOAT OPTIONAL leaves, Data Page V1, PLAIN values, RLE/bit-packed definition levels, UNCOMPRESSED or
 * LZ4_RAW, any number of row groups — with the parquet-format ≥ 2.13 footer statistics the v6
 * firmware also writes (`min_value`/`max_value` + exact flags, `null_count`, `nan_count` on FLOAT,
 * TYPE_ORDER column orders, NaN excluded and ±0.0 normalised) and the `TIMESTAMP(NANOS, UTC)`
 * annotation on request.
 *
 * Streaming: `begin` → `rowGroup(…)`… → `finish`. Each row group is one page per column. Used by the
 * derived daily files; validated in tests by the app's own reader and, via `tools/`, by PyArrow and
 * DuckDB.
 */
class ParquetWriter(
    private val out: OutputStream,
    private val columns: List<Column>,
    private val codec: ParquetCodec = ParquetCodec.LZ4_RAW,
    private val keyValueMetadata: Map<String, String> = emptyMap(),
    private val createdBy: String = "AQ Sync Android derived-v1",
    /** Leaf index the rows are sorted by (ascending, nulls last), or null. */
    private val sortedBy: Int? = null,
) {
    /** A leaf column: name, physical type, and whether it is `event_time_utc_ns`-like (TIMESTAMP NANOS UTC). */
    data class Column(val name: String, val type: ParquetType, val timestampNanosUtc: Boolean = false) {
        init {
            require(type == ParquetType.INT32 || type == ParquetType.INT64 || type == ParquetType.FLOAT) { "unsupported type $type for '$name'" }
        }
    }

    private class ChunkRecord(
        val column: Column,
        val pageOffset: Long,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val numValues: Long,
        val nullCount: Long,
        val nanCount: Long,
        val min: ByteArray?,
        val max: ByteArray?,
    )

    private class GroupRecord(val chunks: List<ChunkRecord>, val rows: Long, val fileOffset: Long)

    private val groups = ArrayList<GroupRecord>()
    private var position = 0L
    private var begun = false
    private var finished = false

    init {
        require(codec == ParquetCodec.UNCOMPRESSED || codec == ParquetCodec.LZ4_RAW) { "codec $codec not supported" }
        require(columns.isNotEmpty()) { "no columns" }
    }

    fun begin() {
        check(!begun)
        begun = true
        write(MAGIC)
    }

    /**
     * Writes one row group. [values] holds one [ColumnValues] per column, all of the same size; each
     * column's physical type must match its [Column]. An empty group is skipped (Parquet allows it,
     * readers dislike it).
     */
    fun rowGroup(values: List<ColumnValues>) {
        check(begun && !finished)
        require(values.size == columns.size) { "${values.size} columns given, ${columns.size} declared" }
        val rows = values.firstOrNull()?.size ?: 0
        if (rows == 0) return
        val groupStart = position
        val chunks = ArrayList<ChunkRecord>(columns.size)
        for ((i, column) in columns.withIndex()) {
            val col = values[i]
            require(col.size == rows) { "'${column.name}' has ${col.size} rows, expected $rows" }
            chunks += writeChunk(column, col)
        }
        groups += GroupRecord(chunks, rows.toLong(), groupStart)
    }

    private fun writeChunk(column: Column, col: ColumnValues): ChunkRecord {
        val rows = col.size
        val valid = col.valid
        val defined = rows - col.nullCount
        // --- page body: definition levels (i32 LE length + RLE/bit-packed), then PLAIN values.
        val body = ByteArrayOutputStream()
        val levels = encodeLevels(valid, rows)
        body.write(le32(levels.size))
        body.write(levels)
        val width = column.type.plainWidth
        val plain = ByteBuffer.allocate(defined * width).order(ByteOrder.LITTLE_ENDIAN)
        var nan = 0L
        var minL = Long.MAX_VALUE
        var maxL = Long.MIN_VALUE
        var minF = Float.POSITIVE_INFINITY
        var maxF = Float.NEGATIVE_INFINITY
        var anyFinite = false
        when (col) {
            is ColumnValues.Int32 -> {
                require(column.type == ParquetType.INT32) { "'${column.name}' declared ${column.type}, got INT32 values" }
                for (i in 0 until rows) {
                    if (valid != null && !valid[i]) continue
                    val v = col.values[i]
                    plain.putInt(v)
                    if (v < minL) minL = v.toLong()
                    if (v > maxL) maxL = v.toLong()
                }
            }
            is ColumnValues.Int64 -> {
                require(column.type == ParquetType.INT64) { "'${column.name}' declared ${column.type}, got INT64 values" }
                for (i in 0 until rows) {
                    if (valid != null && !valid[i]) continue
                    val v = col.values[i]
                    plain.putLong(v)
                    if (v < minL) minL = v
                    if (v > maxL) maxL = v
                }
            }
            is ColumnValues.Float32 -> {
                require(column.type == ParquetType.FLOAT) { "'${column.name}' declared ${column.type}, got FLOAT values" }
                for (i in 0 until rows) {
                    if (valid != null && !valid[i]) continue
                    val v = col.values[i]
                    plain.putFloat(v)
                    if (v.isNaN()) { nan++; continue }
                    anyFinite = true
                    if (v < minF) minF = v
                    if (v > maxF) maxF = v
                }
            }
        }
        body.write(plain.array())
        val uncompressed = body.toByteArray()
        val compressed = if (codec == ParquetCodec.LZ4_RAW) Lz4Block.compress(uncompressed) else uncompressed

        // --- statistics bytes (PLAIN LE), following the 2.13 float rules.
        var min: ByteArray? = null
        var max: ByteArray? = null
        if (defined > 0) {
            when (column.type) {
                ParquetType.INT32 -> if (minL != Long.MAX_VALUE) { min = le32(minL.toInt()); max = le32(maxL.toInt()) }
                ParquetType.INT64 -> if (minL != Long.MAX_VALUE) { min = le64(minL); max = le64(maxL) }
                else -> if (anyFinite) {
                    // Zeros: min is written as -0.0 and max as +0.0 so either sign compares correctly.
                    val lo = if (minF == 0f) -0.0f else minF
                    val hi = if (maxF == 0f) 0.0f else maxF
                    min = leF(lo); max = leF(hi)
                }
            }
        }

        // --- page header, then the page.
        val header = ThriftCompactWriter().apply {
            structBegin()
            i32(1, 0) // DATA_PAGE
            i32(2, uncompressed.size)
            i32(3, compressed.size)
            struct(5) {
                i32(1, rows) // num_values incl. nulls
                i32(2, ParquetEncoding.PLAIN.code)
                i32(3, ParquetEncoding.RLE.code) // definition levels
                i32(4, ParquetEncoding.RLE.code) // repetition levels (none written: max level 0)
            }
            structEnd()
        }.toByteArray()
        val pageOffset = position
        write(header)
        write(compressed)
        return ChunkRecord(column, pageOffset, (header.size + compressed.size).toLong(), (header.size + uncompressed.size).toLong(), rows.toLong(), (rows - defined).toLong(), nan, min, max)
    }

    /** RLE/bit-packed hybrid for bit width 1: one RLE run when uniform, else bit-packed groups of 8. */
    private fun encodeLevels(valid: BooleanArray?, rows: Int): ByteArray {
        val out = ByteArrayOutputStream()
        fun varint(v: Long) { var x = v; while (x and 0x7FL.inv() != 0L) { out.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7 }; out.write(x.toInt()) }
        if (valid == null || valid.all { it } || valid.none { it }) {
            varint((rows.toLong() shl 1)) // RLE run header, count = rows
            out.write(if (valid == null || valid.all { it }) 1 else 0)
            return out.toByteArray()
        }
        val groupCount = (rows + 7) / 8
        varint((groupCount.toLong() shl 1) or 1L)
        for (g in 0 until groupCount) {
            var byte = 0
            for (b in 0 until 8) {
                val i = g * 8 + b
                if (i < rows && valid[i]) byte = byte or (1 shl b)
            }
            out.write(byte)
        }
        return out.toByteArray()
    }

    /** Writes the footer and the trailing magic. */
    fun finish() {
        check(begun && !finished)
        finished = true
        val footer = ThriftCompactWriter().apply {
            structBegin()
            i32(1, 2) // version
            // schema: root + leaves
            listBegin(2, Type.STRUCT, columns.size + 1)
            listStruct {
                string(4, "schema")
                i32(5, columns.size)
            }
            for (c in columns) {
                listStruct {
                    i32(1, c.type.code)
                    i32(3, ParquetRepetition.OPTIONAL.code)
                    string(4, c.name)
                    if (c.timestampNanosUtc) {
                        struct(10) { // LogicalType
                            struct(8) { // TIMESTAMP
                                bool(1, true) // isAdjustedToUTC
                                struct(2) { struct(3) { } } // unit = NANOS (empty struct)
                            }
                        }
                    }
                }
            }
            i64(3, groups.sumOf { it.rows })
            listBegin(4, Type.STRUCT, groups.size)
            for ((ordinal, g) in groups.withIndex()) {
                listStruct {
                    listBegin(1, Type.STRUCT, g.chunks.size)
                    for (ch in g.chunks) {
                        listStruct {
                            i64(2, ch.pageOffset) // file_offset (deprecated, still written by everyone)
                            struct(3) { // ColumnMetaData
                                i32(1, ch.column.type.code)
                                listBegin(2, Type.I32, 2)
                                listI32(ParquetEncoding.PLAIN.code)
                                listI32(ParquetEncoding.RLE.code)
                                listBegin(3, Type.BINARY, 1)
                                listString(ch.column.name)
                                i32(4, codec.code)
                                i64(5, ch.numValues)
                                i64(6, ch.uncompressedSize)
                                i64(7, ch.compressedSize)
                                i64(9, ch.pageOffset)
                                struct(12) { // Statistics
                                    i64(3, ch.nullCount)
                                    if (ch.max != null) binary(5, ch.max)
                                    if (ch.min != null) binary(6, ch.min)
                                    if (ch.max != null) bool(7, true)
                                    if (ch.min != null) bool(8, true)
                                    if (ch.column.type == ParquetType.FLOAT) i64(9, ch.nanCount)
                                }
                            }
                        }
                    }
                    i64(2, g.chunks.sumOf { it.uncompressedSize })
                    i64(3, g.rows)
                    if (sortedBy != null) {
                        listBegin(4, Type.STRUCT, 1)
                        listStruct {
                            i32(1, sortedBy)
                            bool(2, false)
                            bool(3, false)
                        }
                    }
                    i64(5, g.fileOffset)
                    i64(6, g.chunks.sumOf { it.compressedSize })
                    i16(7, ordinal)
                }
            }
            if (keyValueMetadata.isNotEmpty()) {
                listBegin(5, Type.STRUCT, keyValueMetadata.size)
                for ((k, v) in keyValueMetadata) listStruct { string(1, k); string(2, v) }
            }
            string(6, createdBy)
            listBegin(7, Type.STRUCT, columns.size)
            repeat(columns.size) { listStruct { struct(1) { } } } // ColumnOrder.TYPE_ORDER
            structEnd()
        }.toByteArray()
        write(footer)
        write(le32(footer.size))
        write(MAGIC)
        out.flush()
    }

    private fun write(bytes: ByteArray) { out.write(bytes); position += bytes.size }

    companion object {
        private val MAGIC = "PAR1".toByteArray(Charsets.US_ASCII)
        private fun le32(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
        private fun le64(v: Long) = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array()
        private fun leF(v: Float) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(v).array()
    }
}
