package earth.walkthru.aqsync.data.history.parquet

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

/**
 * `LogicalType` union. TIMESTAMP (8), STRING (1) and the parquet-format 2.11 geospatial types GEOMETRY (17)
 * and GEOGRAPHY (18) are modelled; everything else keeps its union field id.
 */
sealed interface ParquetLogicalType {
    enum class TimeUnit { MILLIS, MICROS, NANOS, UNKNOWN }

    data class Timestamp(val isAdjustedToUtc: Boolean, val unit: TimeUnit) : ParquetLogicalType

    /** UTF-8 `BYTE_ARRAY`. */
    data object Utf8 : ParquetLogicalType

    /**
     * WKB in a `BYTE_ARRAY`, planar edges. [crs] is the raw string of the annotation (`null` = OGC:CRS84);
     * interpret it with [Crs.parse].
     */
    data class Geometry(val crs: String?) : ParquetLogicalType

    /** WKB in a `BYTE_ARRAY`, edges interpolated with [algorithm] (`null` = SPHERICAL). */
    data class Geography(val crs: String?, val algorithm: EdgeInterpolation?) : ParquetLogicalType

    data class Other(val unionFieldId: Int) : ParquetLogicalType

    val isGeospatial: Boolean get() = this is Geometry || this is Geography
}

/** `EdgeInterpolationAlgorithm` of `GeographyType`. */
enum class EdgeInterpolation(val code: Int) {
    SPHERICAL(0), VINCENTY(1), THOMAS(2), ANDOYER(3), KARNEY(4), UNKNOWN(-1);

    companion object {
        fun of(code: Int): EdgeInterpolation = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/**
 * `BoundingBox` of the geospatial statistics: X/Y always, Z and M when the column has them. X may wrap
 * (`xmin > xmax`) for a box crossing the antimeridian, exactly as the spec allows.
 */
data class BoundingBox(
    val xmin: Double,
    val xmax: Double,
    val ymin: Double,
    val ymax: Double,
    val zmin: Double? = null,
    val zmax: Double? = null,
    val mmin: Double? = null,
    val mmax: Double? = null,
) {
    val wrapsAntimeridian: Boolean get() = xmin > xmax

    /** True when a point may lie inside (X honouring the wrap rule). Nulls in Z/M are ignored. */
    fun mayContain(x: Double, y: Double): Boolean {
        val xIn = if (wrapsAntimeridian) x >= xmin || x <= xmax else x in xmin..xmax
        return xIn && y in ymin..ymax
    }

    /** The union of two boxes (no antimeridian reasoning: a wrapped input yields a wrapped output only when identical). */
    fun union(other: BoundingBox): BoundingBox = BoundingBox(
        minOf(xmin, other.xmin),
        maxOf(xmax, other.xmax),
        minOf(ymin, other.ymin),
        maxOf(ymax, other.ymax),
        minOrNull(zmin, other.zmin),
        maxOrNull(zmax, other.zmax),
        minOrNull(mmin, other.mmin),
        maxOrNull(mmax, other.mmax),
    )

    private fun minOrNull(a: Double?, b: Double?) = if (a == null) {
        b
    } else if (b == null) {
        a
    } else {
        minOf(a, b)
    }
    private fun maxOrNull(a: Double?, b: Double?) = if (a == null) {
        b
    } else if (b == null) {
        a
    } else {
        maxOf(a, b)
    }
}

/**
 * `GeospatialStatistics` (`ColumnMetaData` field 17): the bbox of every geometry in the chunk and the ISO
 * WKB type codes present (1–7, +1000 Z, +2000 M, +3000 ZM); an empty list means "unknown".
 */
data class GeospatialStatistics(val bbox: BoundingBox?, val geospatialTypes: List<Int>)

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

    /** GEOMETRY or GEOGRAPHY annotated (GeoParquet 2.0 native form). */
    val isGeospatial: Boolean get() = logicalType?.isGeospatial == true
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
    fun minFloat(): Float? = minValue?.takeIf { it.size == 4 }?.let { Float.fromBits(leInt(it, 0)) }
    fun maxFloat(): Float? = maxValue?.takeIf { it.size == 4 }?.let { Float.fromBits(leInt(it, 0)) }
    fun minDouble(): Double? = minValue?.takeIf { it.size == 8 }?.let { Double.fromBits(leLong(it, 0)) }
    fun maxDouble(): Double? = maxValue?.takeIf { it.size == 8 }?.let { Double.fromBits(leLong(it, 0)) }

    internal fun decodeLegacy(bytes: ByteArray, type: ParquetType): Long? = decodeLong(bytes, type)

    private fun decodeLong(bytes: ByteArray, type: ParquetType): Long? = when {
        type == ParquetType.INT32 && bytes.size == 4 -> leInt(bytes, 0).toLong()
        type == ParquetType.INT64 && bytes.size == 8 -> leLong(bytes, 0)
        else -> null
    }
}

/**
 * Footer statistics of one INT32/INT64 column chunk ([ParquetFileMetadata.longStatistics]). [min] and
 * [max] are null only when every value of the row group is null.
 */
data class LongStatistics(val rowGroup: Int, val numRows: Long, val min: Long?, val max: Long?, val nullCount: Long?)

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
    /** parquet-format 2.11 `geospatial_statistics`, present on GEOMETRY/GEOGRAPHY chunks from writers that compute it. */
    val geospatialStatistics: GeospatialStatistics? = null,
    /** File bytes of this chunk's `OffsetIndex` (ColumnChunk fields 4 and 5), null when the writer wrote no page index. */
    val offsetIndex: LongRange? = null,
    /** File bytes of this chunk's `ColumnIndex` (ColumnChunk fields 6 and 7), null when absent. */
    val columnIndex: LongRange? = null,
) {
    val name: String get() = if (pathInSchema.size == 1) pathInSchema[0] else pathInSchema.joinToString(".")

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
    /**
     * The leaves in schema order. Our files are flat, so this is `schema.drop(1)`; nested groups are rejected by
     * the parser. A BYTE_ARRAY with only the legacy `ConvertedType UTF8` (DuckDB) is reported as [ParquetLogicalType.Utf8].
     */
    val leaves: List<LeafColumn> = schema.drop(1).mapIndexed { i, e ->
        LeafColumn(
            index = i,
            name = e.name,
            type = e.type ?: ParquetType.UNKNOWN,
            repetition = e.repetition ?: ParquetRepetition.OPTIONAL,
            logicalType = e.logicalType ?: if (e.convertedType == CONVERTED_UTF8 && e.type == ParquetType.BYTE_ARRAY) ParquetLogicalType.Utf8 else null,
        )
    }

    private val leafByName: Map<String, LeafColumn> by lazy {
        val map = HashMap<String, LeafColumn>(leaves.size * 2)
        for (leaf in leaves) if (leaf.name !in map) map[leaf.name] = leaf
        map
    }

    /** The first leaf called [name], from a map built once per footer. */
    fun leaf(name: String): LeafColumn? = leafByName[name]

    /**
     * The footer min and max of an INT64 (or INT32) column in every row group, without reading a page.
     * One entry per row group, in order; an entry is null when that chunk has no usable statistics (the
     * v1 firmware writer, a writer that skipped them). The deprecated `min`/`max` fields are used when the
     * current ones are absent, which is sound for signed integers.
     *
     * @throws ParquetFormatException when [column] does not exist or is not INT32/INT64.
     */
    fun longStatistics(column: String): List<LongStatistics?> {
        val leaf = leaf(column) ?: throw ParquetFormatException("no column '$column'")
        if (leaf.type != ParquetType.INT64 && leaf.type != ParquetType.INT32) throw ParquetFormatException("'$column' is ${leaf.type}, not INT32/INT64")
        return rowGroups.mapIndexed { g, rg ->
            val chunk = rg.columns[leaf.index]
            val st = chunk.statistics
            val min = st?.minLong(leaf.type) ?: st?.legacyMin?.let { st.decodeLegacy(it, leaf.type) }
            val max = st?.maxLong(leaf.type) ?: st?.legacyMax?.let { st.decodeLegacy(it, leaf.type) }
            when {
                min != null && max != null -> LongStatistics(g, rg.numRows, min, max, st?.nullCount)
                st?.nullCount != null && st.nullCount == rg.numRows -> LongStatistics(g, rg.numRows, null, null, st.nullCount)
                else -> null
            }
        }
    }

    /**
     * The row groups whose [column] values may fall inside one of [ranges] (inclusive), judged by the footer
     * statistics alone. A row group without statistics is kept (it cannot be ruled out); one whose values
     * are all null is dropped. The caller still filters the decoded rows exactly. Page-index pruning is a
     * later step (docs/parquet.md).
     */
    fun rowGroupsOverlapping(column: String, ranges: List<LongRange>): List<Int> {
        val stats = longStatistics(column)
        return stats.indices.filter { g ->
            val s = stats[g] ?: return@filter true
            val lo = s.min ?: return@filter false
            val hi = s.max ?: return@filter false
            ranges.any { r -> !r.isEmpty() && r.first <= hi && r.last >= lo }
        }
    }

    /** The GeoParquet `geo` key, parsed leniently, or null when absent or unparseable. */
    val geo: GeoParquetMetadata? by lazy { keyValueMetadata["geo"]?.let { GeoParquetMetadata.parse(it) } }

    /**
     * Every geometry column of the file, whichever way it is declared: the Parquet-native GEOMETRY/GEOGRAPHY
     * logical type (GeoParquet 2.0, or a plain Parquet writer with no `geo` key) and/or the `geo` key
     * (GeoParquet 1.x: WKB in an unannotated BYTE_ARRAY). The logical type wins on CRS/edges; `geo` fills
     * `geometry_types`, `bbox`, `orientation` and provides the primary column.
     */
    val geometryColumns: List<GeometryColumn> by lazy {
        val fromGeo = geo?.columns.orEmpty()
        val names = LinkedHashSet<String>()
        leaves.filter { it.isGeospatial }.forEach { names += it.name }
        fromGeo.keys.filter { leaf(it) != null }.forEach { names += it }
        names.mapNotNull { name ->
            val leaf = leaf(name) ?: return@mapNotNull null
            val meta = fromGeo[name]
            if (leaf.type != ParquetType.BYTE_ARRAY) return@mapNotNull null
            if (meta != null && meta.encoding != null && meta.encoding != "WKB") return@mapNotNull null // GeoArrow native encodings of 1.1 are nested: not this reader
            val logical = leaf.logicalType
            val crs = when (logical) {
                is ParquetLogicalType.Geometry -> Crs.parse(logical.crs, keyValueMetadata)
                is ParquetLogicalType.Geography -> Crs.parse(logical.crs, keyValueMetadata)
                else -> Crs.fromGeoJson(meta?.crs)
            }
            val edges = when {
                logical is ParquetLogicalType.Geography -> (logical.algorithm ?: EdgeInterpolation.SPHERICAL).name.lowercase()
                logical is ParquetLogicalType.Geometry -> "planar"
                else -> meta?.edges ?: "planar"
            }
            GeometryColumn(
                leaf = leaf,
                crs = crs,
                edges = edges,
                primary = geo?.primaryColumn == name || (geo == null && names.first() == name),
                geometryTypes = meta?.geometryTypes,
                bbox = meta?.bbox,
                orientation = meta?.orientation,
                statistics = rowGroups.mapNotNull { it.columns.getOrNull(leaf.index)?.geospatialStatistics },
            )
        }
    }

    /** Firmware file metadata: `schema_version` (`cores3-telemetry-v1|v2|v3`), `station_id`, `boot_id`, … */
    val schemaVersion: String? get() = keyValueMetadata["schema_version"]

    private companion object {
        const val CONVERTED_UTF8 = 0
    }
    val stationId: String? get() = keyValueMetadata["station_id"]
    val bootId: String? get() = keyValueMetadata["boot_id"]
}
