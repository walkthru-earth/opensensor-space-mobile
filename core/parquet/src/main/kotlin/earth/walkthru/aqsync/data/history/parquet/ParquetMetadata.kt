package earth.walkthru.aqsync.data.history.parquet

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The parts of a Parquet footer this app models. Everything the firmware writes (v1 `0.1` writer:
 * one row group, `null_count` only; v6 `0.2` writer: up to 8 row groups, `min_value`/`max_value`,
 * exact flags, `nan_count`, `column_orders`, `TIMESTAMP(NANOS, UTC)`) and enough of the
 * parquet-format vocabulary to *recognise* what we do not decode (other physical types, dictionary
 * pages, other codecs) and refuse it with a clear message instead of misreading it.
 *
 * Enum numbers are the `parquet.thrift` values.
 */
enum class ParquetType(val code: Int) {
    BOOLEAN(0), INT32(1), INT64(2), INT96(3), FLOAT(4), DOUBLE(5), BYTE_ARRAY(6), FIXED_LEN_BYTE_ARRAY(7), UNKNOWN(-1);

    /** Bytes per PLAIN value for the fixed-width types we decode. */
    val plainWidth: Int
        get() = when (this) {
            INT32, FLOAT -> 4
            INT64, DOUBLE -> 8
            else -> -1
        }

    companion object {
        fun of(code: Int): ParquetType = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

enum class ParquetCodec(val code: Int) {
    UNCOMPRESSED(0), SNAPPY(1), GZIP(2), LZO(3), BROTLI(4), LZ4(5), ZSTD(6), LZ4_RAW(7), UNKNOWN(-1);

    companion object {
        fun of(code: Int): ParquetCodec = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

enum class ParquetEncoding(val code: Int) {
    PLAIN(0), PLAIN_DICTIONARY(2), RLE(3), BIT_PACKED(4), DELTA_BINARY_PACKED(5), DELTA_LENGTH_BYTE_ARRAY(6),
    DELTA_BYTE_ARRAY(7), RLE_DICTIONARY(8), BYTE_STREAM_SPLIT(9), UNKNOWN(-1);

    companion object {
        fun of(code: Int): ParquetEncoding = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

enum class ParquetRepetition(val code: Int) {
    REQUIRED(0), OPTIONAL(1), REPEATED(2), UNKNOWN(-1);

    companion object {
        fun of(code: Int): ParquetRepetition = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/** `LogicalType` union. Only TIMESTAMP is modelled; everything else keeps its union field id. */
sealed interface ParquetLogicalType {
    enum class TimeUnit { MILLIS, MICROS, NANOS, UNKNOWN }

    data class Timestamp(val isAdjustedToUtc: Boolean, val unit: TimeUnit) : ParquetLogicalType

    data class Other(val unionFieldId: Int) : ParquetLogicalType
}

data class SchemaElement(
    val name: String,
    /** Null for the root and for group nodes. */
    val type: ParquetType?,
    val typeLength: Int?,
    val repetition: ParquetRepetition?,
    val numChildren: Int,
    val convertedType: Int?,
    val fieldId: Int?,
    val logicalType: ParquetLogicalType?,
) {
    val isLeaf: Boolean get() = numChildren == 0 && type != null
}

/** A leaf column as the reader sees it: flat, one level below the root. */
data class LeafColumn(
    /** Index among the leaves, which is also the index into `RowGroup.columns`. */
    val index: Int,
    val name: String,
    val type: ParquetType,
    val repetition: ParquetRepetition,
    val logicalType: ParquetLogicalType?,
) {
    val maxDefinitionLevel: Int get() = if (repetition == ParquetRepetition.REQUIRED) 0 else 1
    val isTimestampNanosUtc: Boolean
        get() = (logicalType as? ParquetLogicalType.Timestamp)?.let { it.isAdjustedToUtc && it.unit == ParquetLogicalType.TimeUnit.NANOS } == true
}

/**
 * `Statistics`. `minValue`/`maxValue` are the parquet-format ≥ 2.x fields (5/6); `legacyMin`/`legacyMax`
 * the deprecated 1/2 which the firmware never writes. Raw PLAIN little-endian bytes; decode with
 * [minLong]/[maxLong]/[minFloat]/[maxFloat] according to the column's physical type.
 */
class ColumnStatistics(
    val nullCount: Long?,
    val distinctCount: Long?,
    val minValue: ByteArray?,
    val maxValue: ByteArray?,
    val isMinValueExact: Boolean?,
    val isMaxValueExact: Boolean?,
    /** parquet-format 2.13: NaNs excluded from min/max and counted here. */
    val nanCount: Long?,
    val legacyMin: ByteArray?,
    val legacyMax: ByteArray?,
) {
    val hasMinMax: Boolean get() = minValue != null && maxValue != null

    fun minLong(type: ParquetType): Long? = minValue?.let { decodeLong(it, type) }
    fun maxLong(type: ParquetType): Long? = maxValue?.let { decodeLong(it, type) }
    fun minFloat(): Float? = minValue?.takeIf { it.size == 4 }?.let { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).float }
    fun maxFloat(): Float? = maxValue?.takeIf { it.size == 4 }?.let { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).float }

    private fun decodeLong(bytes: ByteArray, type: ParquetType): Long? {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return when {
            type == ParquetType.INT32 && bytes.size == 4 -> b.int.toLong()
            type == ParquetType.INT64 && bytes.size == 8 -> b.long
            else -> null
        }
    }
}

data class ColumnChunkMeta(
    val type: ParquetType,
    val encodings: List<ParquetEncoding>,
    val pathInSchema: List<String>,
    val codec: ParquetCodec,
    /** Values in the chunk *including* nulls (one per row for a flat column). */
    val numValues: Long,
    val totalUncompressedSize: Long,
    val totalCompressedSize: Long,
    val dataPageOffset: Long,
    val indexPageOffset: Long?,
    val dictionaryPageOffset: Long?,
    val statistics: ColumnStatistics?,
    /** `ColumnChunk.file_offset` (deprecated but written); the chunk's own path is always null for us. */
    val chunkFileOffset: Long,
) {
    val name: String get() = pathInSchema.joinToString(".")

    /** Where the first page header starts: the dictionary page, if any, comes before the data pages. */
    val firstPageOffset: Long get() = dictionaryPageOffset?.takeIf { it > 0 && it < dataPageOffset } ?: dataPageOffset
}

data class RowGroupMeta(
    val columns: List<ColumnChunkMeta>,
    val totalByteSize: Long,
    val numRows: Long,
    val sortingColumns: List<SortingColumn>,
    val fileOffset: Long?,
    val totalCompressedSize: Long?,
    val ordinal: Int?,
)

data class SortingColumn(val columnIdx: Int, val descending: Boolean, val nullsFirst: Boolean)

data class ParquetFileMetadata(
    val version: Int,
    val schema: List<SchemaElement>,
    val numRows: Long,
    val rowGroups: List<RowGroupMeta>,
    val keyValueMetadata: Map<String, String>,
    val createdBy: String?,
    /** One entry per leaf when present: the `ColumnOrder` union field id (1 = TYPE_ORDER); empty on v1 files. */
    val columnOrders: List<Int>,
    /** Size of the serialized footer in bytes (the number before the trailing `PAR1`). */
    val footerLength: Int,
) {
    /** The leaves in schema order. Our files are flat, so this is `schema.drop(1)`; nested groups are rejected by the parser. */
    val leaves: List<LeafColumn> = schema.drop(1).mapIndexed { i, e ->
        LeafColumn(
            index = i,
            name = e.name,
            type = e.type ?: ParquetType.UNKNOWN,
            repetition = e.repetition ?: ParquetRepetition.OPTIONAL,
            logicalType = e.logicalType,
        )
    }

    fun leaf(name: String): LeafColumn? = leaves.firstOrNull { it.name == name }

    /** Firmware file metadata: `schema_version` (`cores3-telemetry-v1|v2|v3`), `station_id`, `boot_id`, … */
    val schemaVersion: String? get() = keyValueMetadata["schema_version"]
    val stationId: String? get() = keyValueMetadata["station_id"]
    val bootId: String? get() = keyValueMetadata["boot_id"]
}
