package earth.walkthru.aqsync.data.history.parquet

import earth.walkthru.aqsync.data.history.parquet.ThriftCompactReader.Type

/** Thrown for a file this reader does not understand — a malformed footer or a feature outside the firmware's subset. */
class ParquetFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Parses the `FileMetaData` footer of a Parquet file: `… <footer bytes> <i32 LE footer length> "PAR1"`.
 * Unknown fields are skipped, so a newer parquet-format never breaks the phone; unknown *values*
 * (a physical type or codec we do not decode) are kept as `UNKNOWN` and refused only when a column
 * is actually read.
 */
object ParquetFooterParser {

    internal val MAGIC = "PAR1".encodeToByteArray()

    /** [file] is the whole file. */
    fun parse(file: ByteArray): ParquetFileMetadata {
        val size = file.size.toLong()
        if (size < 12) throw ParquetFormatException("file too small ($size bytes)")
        if (!file.copyOfRange(0, 4).contentEquals(MAGIC)) throw ParquetFormatException("PAR1 magic missing")
        val footerLength = footerLength(file.copyOfRange(file.size - 8, file.size), size)
        return parseFooter(file, (size - 8 - footerLength).toInt(), footerLength)
    }

    /**
     * Reads the footer through [reader] with one tail read of up to [DEFAULT_TAIL_BYTES], which holds the
     * whole footer of every file the app writes. A longer footer costs one more read, and the head magic
     * is read on its own only when the tail does not reach the start of the file. Never the column data.
     */
    fun parse(reader: ByteReader): ParquetFileMetadata {
        val size = reader.size
        if (size < 12) throw ParquetFormatException("file too small ($size bytes)")
        val tailLength = minOf(size, DEFAULT_TAIL_BYTES.toLong()).toInt()
        val tailStart = size - tailLength
        if (tailStart > 0 && !reader.read(0, 4).contentEquals(MAGIC)) throw ParquetFormatException("PAR1 magic missing")
        return parseTail(ByteView(reader.read(tailStart, tailLength)), size) { start, length -> ByteView(reader.read(start, length)) }
    }

    /** First read of a file: holds the footer of every file the app and the public feed write. */
    const val DEFAULT_TAIL_BYTES: Int = 64 * 1024

    /**
     * Parses the footer from [tail], the last bytes of a file of [size] bytes. When the tail reaches the
     * start of the file its head `PAR1` is checked too. When the footer is longer than the tail,
     * [readMissing] fetches the bytes before it and the two are joined once.
     */
    internal inline fun parseTail(tail: ByteView, size: Long, readMissing: (start: Long, length: Int) -> ByteView): ParquetFileMetadata {
        if (size < 12 || tail.length < 8) throw ParquetFormatException("file too small ($size bytes)")
        val tailStart = size - tail.length
        if (tailStart == 0L && !(tail[0] == MAGIC[0] && tail[1] == MAGIC[1] && tail[2] == MAGIC[2] && tail[3] == MAGIC[3])) throw ParquetFormatException("PAR1 magic missing")
        val footerLength = footerLength(tail.array, tail.offset + tail.length - 8, size)
        val footerStart = size - 8 - footerLength
        if (footerStart >= tailStart) return parseFooter(tail.array, tail.offset + (footerStart - tailStart).toInt(), footerLength)
        val missing = readMissing(footerStart, (tailStart - footerStart).toInt())
        val joined = ByteArray(footerLength)
        missing.array.copyInto(joined, 0, missing.offset, missing.offset + missing.length)
        tail.array.copyInto(joined, missing.length, tail.offset, tail.offset + footerLength - missing.length)
        return parseFooter(joined, 0, footerLength)
    }

    /** Validates the trailing `<i32 LE length> PAR1` of a file of [size] bytes and returns the footer length. */
    internal fun footerLength(tail8: ByteArray, size: Long): Int = footerLength(tail8, 0, size)

    /** The same, for the 8 bytes at [at] of [bytes]. */
    internal fun footerLength(bytes: ByteArray, at: Int, size: Long): Int {
        if (size < 12) throw ParquetFormatException("file too small ($size bytes)")
        if (bytes[at + 4] != MAGIC[0] || bytes[at + 5] != MAGIC[1] || bytes[at + 6] != MAGIC[2] || bytes[at + 7] != MAGIC[3]) throw ParquetFormatException("PAR1 magic missing")
        val footerLength = leInt(bytes, at)
        if (footerLength <= 0 || footerLength > size - 12) throw ParquetFormatException("footer length $footerLength out of range for $size bytes")
        return footerLength
    }

    /** Parses the Thrift `FileMetaData` held in `bytes[offset, offset + length)`. */
    internal fun parseFooter(bytes: ByteArray, offset: Int, length: Int): ParquetFileMetadata = try {
        readFileMetaData(ThriftCompactReader(ByteCursor(bytes, offset, offset + length)), length)
    } catch (e: ParquetFormatException) {
        throw e
    } catch (e: RuntimeException) {
        throw ParquetFormatException("malformed footer: ${e.message}", e)
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
        if (root.numChildren != leafCount) throw ParquetFormatException("nested schema (root has ${root.numChildren} children, $leafCount elements): only flat schemas are supported")
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

    /** `LogicalType` is a union: exactly one struct field set; 1 STRING, 8 TIMESTAMP, 17 GEOMETRY, 18 GEOGRAPHY. */
    private fun readLogicalType(r: ThriftCompactReader): ParquetLogicalType? {
        var result: ParquetLogicalType? = null
        r.readStruct { f ->
            result = when {
                f.type != Type.STRUCT -> { r.skip(f.type); ParquetLogicalType.Other(f.id) }
                f.id == 1 -> { r.skip(f.type); ParquetLogicalType.Utf8 }
                f.id == 8 -> readTimestampType(r)
                f.id == 17 -> readGeometryType(r)
                f.id == 18 -> readGeographyType(r)
                else -> { r.skip(f.type); ParquetLogicalType.Other(f.id) }
            }
        }
        return result
    }

    private fun readGeometryType(r: ThriftCompactReader): ParquetLogicalType.Geometry {
        var crs: String? = null
        r.readStruct { f ->
            when (f.id) {
                1 -> crs = r.readString()
                else -> r.skip(f.type)
            }
        }
        return ParquetLogicalType.Geometry(crs)
    }

    private fun readGeographyType(r: ThriftCompactReader): ParquetLogicalType.Geography {
        var crs: String? = null
        var algorithm: EdgeInterpolation? = null
        r.readStruct { f ->
            when (f.id) {
                1 -> crs = r.readString()
                2 -> algorithm = EdgeInterpolation.of(r.readI32())
                else -> r.skip(f.type)
            }
        }
        return ParquetLogicalType.Geography(crs, algorithm)
    }

    private fun readGeospatialStatistics(r: ThriftCompactReader): GeospatialStatistics {
        var bbox: BoundingBox? = null
        var types: List<Int> = emptyList()
        r.readStruct { f ->
            when (f.id) {
                1 -> bbox = readBoundingBox(r)
                2 -> types = readList(r) { r.readI32() }
                else -> r.skip(f.type)
            }
        }
        return GeospatialStatistics(bbox, types)
    }

    private fun readBoundingBox(r: ThriftCompactReader): BoundingBox {
        val v = arrayOfNulls<Double>(8)
        r.readStruct { f ->
            if (f.id in 1..8 && f.type == Type.DOUBLE) v[f.id - 1] = r.readDouble() else r.skip(f.type)
        }
        val xmin = v[0]
        val xmax = v[1]
        val ymin = v[2]
        val ymax = v[3]
        if (xmin == null || xmax == null || ymin == null || ymax == null) throw ParquetFormatException("geospatial bounding box without X/Y")
        return BoundingBox(xmin, xmax, ymin, ymax, v[4], v[5], v[6], v[7])
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
        var meta: ColumnMetaDataFields? = null
        var offsetIndexOffset: Long? = null
        var offsetIndexLength: Int? = null
        var columnIndexOffset: Long? = null
        var columnIndexLength: Int? = null
        r.readStruct { f ->
            when (f.id) {
                1 -> r.readString() // file_path: never set by our writers (single-file)
                2 -> fileOffset = r.readI64()
                3 -> meta = readColumnMetaData(r)
                4 -> offsetIndexOffset = r.readI64()
                5 -> offsetIndexLength = r.readI32()
                6 -> columnIndexOffset = r.readI64()
                7 -> columnIndexLength = r.readI32()
                else -> r.skip(f.type)
            }
        }
        val m = meta ?: throw ParquetFormatException("column chunk without meta_data")
        return ColumnChunkMeta(
            m.type, m.encodings, m.path, m.codec, m.numValues, m.uncompressed, m.compressed, m.dataPageOffset, m.indexPageOffset,
            m.dictionaryPageOffset, m.statistics, chunkFileOffset = fileOffset, geospatialStatistics = m.geo,
            offsetIndex = pageIndexRange(offsetIndexOffset, offsetIndexLength),
            columnIndex = pageIndexRange(columnIndexOffset, columnIndexLength),
        )
    }

    /** A page index pointer, or null when either half is missing or out of range. */
    private fun pageIndexRange(offset: Long?, length: Int?): LongRange? =
        if (offset == null || length == null || offset < 0 || length <= 0) null else offset until offset + length

    /** `ColumnMetaData` before it becomes a [ColumnChunkMeta], so the chunk is built once with its page index pointers. */
    private class ColumnMetaDataFields(
        val type: ParquetType,
        val encodings: List<ParquetEncoding>,
        val path: List<String>,
        val codec: ParquetCodec,
        val numValues: Long,
        val uncompressed: Long,
        val compressed: Long,
        val dataPageOffset: Long,
        val indexPageOffset: Long?,
        val dictionaryPageOffset: Long?,
        val statistics: ColumnStatistics?,
        val geo: GeospatialStatistics?,
    )

    private fun readColumnMetaData(r: ThriftCompactReader): ColumnMetaDataFields {
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
        var geo: GeospatialStatistics? = null
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
                17 -> geo = readGeospatialStatistics(r)
                else -> r.skip(f.type)
            }
        }
        return ColumnMetaDataFields(type, encodings, path, codec, numValues, uncompressed, compressed, dataPageOffset, indexPageOffset, dictionaryPageOffset, statistics, geo)
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
