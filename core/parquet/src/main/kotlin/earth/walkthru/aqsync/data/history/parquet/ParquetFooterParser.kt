package earth.walkthru.aqsync.data.history.parquet

import earth.walkthru.aqsync.data.history.parquet.ThriftCompactReader.Type
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Thrown for a file this reader does not understand — a malformed footer or a feature outside the firmware's subset. */
class ParquetFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Parses the `FileMetaData` footer of a Parquet file: `… <footer bytes> <i32 LE footer length> "PAR1"`.
 * Unknown fields are skipped, so a newer parquet-format never breaks the phone; unknown *values*
 * (a physical type or codec we do not decode) are kept as `UNKNOWN` and refused only when a column
 * is actually read.
 */
object ParquetFooterParser {

    private val MAGIC = "PAR1".toByteArray(Charsets.US_ASCII)

    /** [file] is the whole file, position-independent (absolute reads only). */
    fun parse(file: ByteBuffer): ParquetFileMetadata {
        val size = file.limit()
        if (size < 12) throw ParquetFormatException("file too small ($size bytes)")
        val tail = ByteArray(4) { file.get(size - 4 + it) }
        val head = ByteArray(4) { file.get(it) }
        if (!tail.contentEquals(MAGIC) || !head.contentEquals(MAGIC)) throw ParquetFormatException("PAR1 magic missing")
        val footerLength = file.duplicate().order(ByteOrder.LITTLE_ENDIAN).getInt(size - 8)
        if (footerLength <= 0 || footerLength > size - 12) throw ParquetFormatException("footer length $footerLength out of range for $size bytes")
        val footerStart = size - 8 - footerLength
        val footer = file.duplicate().apply { position(footerStart); limit(size - 8) }.slice()
        return try {
            readFileMetaData(ThriftCompactReader(footer), footerLength)
        } catch (e: ParquetFormatException) {
            throw e
        } catch (e: RuntimeException) {
            throw ParquetFormatException("malformed footer: ${e.message}", e)
        }
    }

    private fun readFileMetaData(r: ThriftCompactReader, footerLength: Int): ParquetFileMetadata {
        var version = 0
        var schema: List<SchemaElement> = emptyList()
        var numRows = 0L
        var rowGroups: List<RowGroupMeta> = emptyList()
        val kv = LinkedHashMap<String, String>()
        var createdBy: String? = null
        var columnOrders: List<Int> = emptyList()
        r.readStruct { f ->
            when (f.id) {
                1 -> version = r.readI32()
                2 -> schema = readList(r) { readSchemaElement(r) }
                3 -> numRows = r.readI64()
                4 -> rowGroups = readList(r) { readRowGroup(r) }
                5 -> readList(r) { readKeyValue(r) }.forEach { (k, v) -> kv[k] = v }
                6 -> createdBy = r.readString()
                7 -> columnOrders = readList(r) { readColumnOrder(r) }
                else -> r.skip(f.type)
            }
        }
        if (schema.isEmpty()) throw ParquetFormatException("footer has no schema")
        val root = schema.first()
        val leafCount = schema.size - 1
        if (root.numChildren != leafCount) throw ParquetFormatException("nested schema (root has ${root.numChildren} children, ${leafCount} elements): only flat schemas are supported")
        schema.drop(1).forEach { e -> if (!e.isLeaf) throw ParquetFormatException("group column '${e.name}': only flat schemas are supported") }
        rowGroups.forEachIndexed { g, rg ->
            if (rg.columns.size != leafCount) throw ParquetFormatException("row group $g has ${rg.columns.size} column chunks for $leafCount leaves")
        }
        return ParquetFileMetadata(version, schema, numRows, rowGroups, kv, createdBy, columnOrders, footerLength)
    }

    private inline fun <T> readList(r: ThriftCompactReader, element: () -> T): List<T> {
        val header = r.readListHeader()
        return List(header.size) { element() }
    }

    private fun readKeyValue(r: ThriftCompactReader): Pair<String, String> {
        var key = ""
        var value = ""
        r.readStruct { f ->
            when (f.id) {
                1 -> key = r.readString()
                2 -> value = r.readString()
                else -> r.skip(f.type)
            }
        }
        return key to value
    }

    private fun readSchemaElement(r: ThriftCompactReader): SchemaElement {
        var type: ParquetType? = null
        var typeLength: Int? = null
        var repetition: ParquetRepetition? = null
        var name = ""
        var numChildren = 0
        var convertedType: Int? = null
        var fieldId: Int? = null
        var logicalType: ParquetLogicalType? = null
        r.readStruct { f ->
            when (f.id) {
                1 -> type = ParquetType.of(r.readI32())
                2 -> typeLength = r.readI32()
                3 -> repetition = ParquetRepetition.of(r.readI32())
                4 -> name = r.readString()
                5 -> numChildren = r.readI32()
                6 -> convertedType = r.readI32()
                9 -> fieldId = r.readI32()
                10 -> logicalType = readLogicalType(r)
                else -> r.skip(f.type)
            }
        }
        return SchemaElement(name, type, typeLength, repetition, numChildren, convertedType, fieldId, logicalType)
    }

    /** `LogicalType` is a union: exactly one struct field set; field 8 is TIMESTAMP. */
    private fun readLogicalType(r: ThriftCompactReader): ParquetLogicalType? {
        var result: ParquetLogicalType? = null
        r.readStruct { f ->
            result = if (f.id == 8 && f.type == Type.STRUCT) readTimestampType(r) else {
                r.skip(f.type)
                ParquetLogicalType.Other(f.id)
            }
        }
        return result
    }

    private fun readTimestampType(r: ThriftCompactReader): ParquetLogicalType.Timestamp {
        var adjusted = false
        var unit = ParquetLogicalType.TimeUnit.UNKNOWN
        r.readStruct { f ->
            when (f.id) {
                1 -> adjusted = r.booleanFromHeader(f)
                2 -> {
                    // TimeUnit union: 1 MILLIS, 2 MICROS, 3 NANOS, each an empty struct.
                    r.readStruct { u ->
                        unit = when (u.id) {
                            1 -> ParquetLogicalType.TimeUnit.MILLIS
                            2 -> ParquetLogicalType.TimeUnit.MICROS
                            3 -> ParquetLogicalType.TimeUnit.NANOS
                            else -> ParquetLogicalType.TimeUnit.UNKNOWN
                        }
                        r.skip(u.type)
                    }
                }
                else -> r.skip(f.type)
            }
        }
        return ParquetLogicalType.Timestamp(adjusted, unit)
    }

    private fun readRowGroup(r: ThriftCompactReader): RowGroupMeta {
        var columns: List<ColumnChunkMeta> = emptyList()
        var totalByteSize = 0L
        var numRows = 0L
        var sorting: List<SortingColumn> = emptyList()
        var fileOffset: Long? = null
        var totalCompressed: Long? = null
        var ordinal: Int? = null
        r.readStruct { f ->
            when (f.id) {
                1 -> columns = readList(r) { readColumnChunk(r) }
                2 -> totalByteSize = r.readI64()
                3 -> numRows = r.readI64()
                4 -> sorting = readList(r) { readSortingColumn(r) }
                5 -> fileOffset = r.readI64()
                6 -> totalCompressed = r.readI64()
                7 -> ordinal = r.readI16()
                else -> r.skip(f.type)
            }
        }
        return RowGroupMeta(columns, totalByteSize, numRows, sorting, fileOffset, totalCompressed, ordinal)
    }

    private fun readSortingColumn(r: ThriftCompactReader): SortingColumn {
        var idx = 0
        var desc = false
        var nullsFirst = false
        r.readStruct { f ->
            when (f.id) {
                1 -> idx = r.readI32()
                2 -> desc = r.booleanFromHeader(f)
                3 -> nullsFirst = r.booleanFromHeader(f)
                else -> r.skip(f.type)
            }
        }
        return SortingColumn(idx, desc, nullsFirst)
    }

    private fun readColumnChunk(r: ThriftCompactReader): ColumnChunkMeta {
        var fileOffset = 0L
        var meta: ColumnChunkMeta? = null
        r.readStruct { f ->
            when (f.id) {
                1 -> r.readString() // file_path: never set by our writers (single-file)
                2 -> fileOffset = r.readI64()
                3 -> meta = readColumnMetaData(r)
                else -> r.skip(f.type)
            }
        }
        return (meta ?: throw ParquetFormatException("column chunk without meta_data")).copy(chunkFileOffset = fileOffset)
    }

    private fun readColumnMetaData(r: ThriftCompactReader): ColumnChunkMeta {
        var type = ParquetType.UNKNOWN
        var encodings: List<ParquetEncoding> = emptyList()
        var path: List<String> = emptyList()
        var codec = ParquetCodec.UNKNOWN
        var numValues = 0L
        var uncompressed = 0L
        var compressed = 0L
        var dataPageOffset = 0L
        var indexPageOffset: Long? = null
        var dictionaryPageOffset: Long? = null
        var statistics: ColumnStatistics? = null
        r.readStruct { f ->
            when (f.id) {
                1 -> type = ParquetType.of(r.readI32())
                2 -> encodings = readList(r) { ParquetEncoding.of(r.readI32()) }
                3 -> path = readList(r) { r.readString() }
                4 -> codec = ParquetCodec.of(r.readI32())
                5 -> numValues = r.readI64()
                6 -> uncompressed = r.readI64()
                7 -> compressed = r.readI64()
                9 -> dataPageOffset = r.readI64()
                10 -> indexPageOffset = r.readI64()
                11 -> dictionaryPageOffset = r.readI64()
                12 -> statistics = readStatistics(r)
                else -> r.skip(f.type)
            }
        }
        return ColumnChunkMeta(type, encodings, path, codec, numValues, uncompressed, compressed, dataPageOffset, indexPageOffset, dictionaryPageOffset, statistics, chunkFileOffset = 0L)
    }

    private fun readStatistics(r: ThriftCompactReader): ColumnStatistics {
        var legacyMax: ByteArray? = null
        var legacyMin: ByteArray? = null
        var nullCount: Long? = null
        var distinctCount: Long? = null
        var maxValue: ByteArray? = null
        var minValue: ByteArray? = null
        var maxExact: Boolean? = null
        var minExact: Boolean? = null
        var nanCount: Long? = null
        r.readStruct { f ->
            when (f.id) {
                1 -> legacyMax = r.readBinary()
                2 -> legacyMin = r.readBinary()
                3 -> nullCount = r.readI64()
                4 -> distinctCount = r.readI64()
                5 -> maxValue = r.readBinary()
                6 -> minValue = r.readBinary()
                7 -> maxExact = r.booleanFromHeader(f)
                8 -> minExact = r.booleanFromHeader(f)
                9 -> nanCount = r.readI64()
                else -> r.skip(f.type)
            }
        }
        return ColumnStatistics(nullCount, distinctCount, minValue, maxValue, minExact, maxExact, nanCount, legacyMin, legacyMax)
    }

    /** `ColumnOrder` union: field 1 = TYPE_ORDER (empty struct). Returns the union field id, 0 if the union is empty. */
    private fun readColumnOrder(r: ThriftCompactReader): Int {
        var which = 0
        r.readStruct { f ->
            which = f.id
            r.skip(f.type)
        }
        return which
    }
}
