package earth.walkthru.aqsync.data.history.parquet

import earth.walkthru.aqsync.data.history.parquet.ThriftCompactReader.Type
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes the same Parquet subset the firmware writes and [ParquetFile] reads — flat INT32 / INT64 /
 * FLOAT / DOUBLE / BYTE_ARRAY OPTIONAL leaves, Data Page V1, PLAIN values, RLE/bit-packed definition
 * levels, UNCOMPRESSED or LZ4_RAW, any number of row groups — with the parquet-format ≥ 2.13 footer
 * statistics the v6 firmware also writes (`min_value`/`max_value` + exact flags, `null_count`,
 * `nan_count` on FLOAT/DOUBLE, TYPE_ORDER column orders, NaN excluded and ±0.0 normalised), the
 * `TIMESTAMP(NANOS, UTC)` and `STRING` annotations on request, and **GeoParquet 2.0**: a BYTE_ARRAY
 * column of WKB annotated `GEOMETRY` gets `geospatial_statistics` (bbox + type codes) per chunk and the
 * file gets the `geo` key (version 2.0.0, WKB, OGC:CRS84 unless a `crs` is given) unless the caller
 * supplied one.
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
    /**
     * A leaf column: name, physical type, and its annotation — `event_time_utc_ns`-like (TIMESTAMP NANOS
     * UTC), UTF-8 string, or a WKB geometry ([geometry], GeoParquet 2.0; `crs` null = OGC:CRS84).
     */
    data class Column(
        val name: String,
        val type: ParquetType,
        val timestampNanosUtc: Boolean = false,
        val utf8: Boolean = false,
        val geometry: GeometryAnnotation? = null,
    ) {
        init {
            require(type in SUPPORTED_TYPES) { "unsupported type $type for '$name'" }
            require(!timestampNanosUtc || type == ParquetType.INT64) { "TIMESTAMP needs INT64 on '$name'" }
            require(!utf8 || type == ParquetType.BYTE_ARRAY) { "STRING needs BYTE_ARRAY on '$name'" }
            require(geometry == null || type == ParquetType.BYTE_ARRAY) { "GEOMETRY needs BYTE_ARRAY on '$name'" }
            require(!(utf8 && geometry != null)) { "'$name' cannot be both STRING and GEOMETRY" }
        }
    }

    /** The `GEOMETRY` logical type: [crs] as the Parquet property (`null` → OGC:CRS84; e.g. `"EPSG:3857"` or inline PROJJSON). */
    data class GeometryAnnotation(val crs: String? = null)

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
        val geo: GeospatialStatistics? = null,
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
        var nan = 0L
        var minL = Long.MAX_VALUE
        var maxL = Long.MIN_VALUE
        var minF = Float.POSITIVE_INFINITY
        var maxF = Float.NEGATIVE_INFINITY
        var minD = Double.POSITIVE_INFINITY
        var maxD = Double.NEGATIVE_INFINITY
        var anyFinite = false
        var minB: ByteArray? = null
        var maxB: ByteArray? = null
        var geoBbox: BoundingBox? = null
        val geoTypes = java.util.TreeSet<Int>()
        val plain: ByteArray
        when (col) {
            is ColumnValues.Int32 -> {
                require(column.type == ParquetType.INT32) { "'${column.name}' declared ${column.type}, got INT32 values" }
                val b = ByteBuffer.allocate(defined * 4).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until rows) {
                    if (valid != null && !valid[i]) continue
                    val v = col.values[i]
                    b.putInt(v)
                    if (v < minL) minL = v.toLong()
                    if (v > maxL) maxL = v.toLong()
                }
                plain = b.array()
            }
            is ColumnValues.Int64 -> {
                require(column.type == ParquetType.INT64) { "'${column.name}' declared ${column.type}, got INT64 values" }
                val b = ByteBuffer.allocate(defined * 8).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until rows) {
                    if (valid != null && !valid[i]) continue
                    val v = col.values[i]
                    b.putLong(v)
                    if (v < minL) minL = v
                    if (v > maxL) maxL = v
                }
                plain = b.array()
            }
            is ColumnValues.Float32 -> {
                require(column.type == ParquetType.FLOAT) { "'${column.name}' declared ${column.type}, got FLOAT values" }
                val b = ByteBuffer.allocate(defined * 4).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until rows) {
                    if (valid != null && !valid[i]) continue
                    val v = col.values[i]
                    b.putFloat(v)
                    if (v.isNaN()) { nan++; continue }
                    anyFinite = true
                    if (v < minF) minF = v
                    if (v > maxF) maxF = v
                }
                plain = b.array()
            }
            is ColumnValues.Float64 -> {
                require(column.type == ParquetType.DOUBLE) { "'${column.name}' declared ${column.type}, got DOUBLE values" }
                val b = ByteBuffer.allocate(defined * 8).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until rows) {
                    if (valid != null && !valid[i]) continue
                    val v = col.values[i]
                    b.putDouble(v)
                    if (v.isNaN()) { nan++; continue }
                    anyFinite = true
                    if (v < minD) minD = v
                    if (v > maxD) maxD = v
                }
                plain = b.array()
            }
            is ColumnValues.Binary -> {
                require(column.type == ParquetType.BYTE_ARRAY) { "'${column.name}' declared ${column.type}, got BYTE_ARRAY values" }
                val b = ByteBuffer.allocate(defined * 4 + col.offsets.last()).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until rows) {
                    if (valid != null && !valid[i]) continue
                    val len = col.length(i)
                    b.putInt(len)
                    b.put(col.data, col.offsets[i], len)
                    if (column.geometry != null) {
                        // Geospatial statistics from the geometry itself; no byte-wise min/max (undefined order for GEOMETRY).
                        val g = Wkb.decode(col.slice(i)!!)
                        geoTypes += g.wkbCode
                        g.bbox()?.let { box -> geoBbox = geoBbox?.union(box) ?: box }
                    } else {
                        val v = col.bytes(i)!!
                        if (minB == null || unsignedCompare(v, minB!!) < 0) minB = v
                        if (maxB == null || unsignedCompare(v, maxB!!) > 0) maxB = v
                    }
                }
                plain = b.array().copyOf(b.position())
            }
        }
        body.write(plain)
        val uncompressed = body.toByteArray()
        val compressed = if (codec == ParquetCodec.LZ4_RAW) Lz4Block.compress(uncompressed) else uncompressed

        // --- statistics bytes (PLAIN LE), following the 2.13 float rules.
        var min: ByteArray? = null
        var max: ByteArray? = null
        if (defined > 0) {
            when (column.type) {
                ParquetType.INT32 -> if (minL != Long.MAX_VALUE) { min = le32(minL.toInt()); max = le32(maxL.toInt()) }
                ParquetType.INT64 -> if (minL != Long.MAX_VALUE) { min = le64(minL); max = le64(maxL) }
                ParquetType.FLOAT -> if (anyFinite) {
                    // Zeros: min is written as -0.0 and max as +0.0 so either sign compares correctly.
                    val lo = if (minF == 0f) -0.0f else minF
                    val hi = if (maxF == 0f) 0.0f else maxF
                    min = leF(lo); max = leF(hi)
                }
                ParquetType.DOUBLE -> if (anyFinite) {
                    val lo = if (minD == 0.0) -0.0 else minD
                    val hi = if (maxD == 0.0) 0.0 else maxD
                    min = leD(lo); max = leD(hi)
                }
                else -> { min = minB; max = maxB } // BYTE_ARRAY strings: unsigned byte order; null for GEOMETRY
            }
        }
        val geo = if (column.geometry != null && defined > 0) GeospatialStatistics(geoBbox, geoTypes.toList()) else null

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
        return ChunkRecord(column, pageOffset, (header.size + compressed.size).toLong(), (header.size + uncompressed.size).toLong(), rows.toLong(), (rows - defined).toLong(), nan, min, max, geo)
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
                    if (c.utf8) i32(6, 0) // ConvertedType UTF8, for pre-LogicalType readers
                    when {
                        c.timestampNanosUtc -> struct(10) {
                            // LogicalType
                            struct(8) {
                                // TIMESTAMP
                                bool(1, true) // isAdjustedToUTC
                                struct(2) { struct(3) { } } // unit = NANOS (empty struct)
                            }
                        }
                        c.utf8 -> struct(10) { struct(1) { } } // STRING
                        c.geometry != null -> struct(10) {
                            struct(17) {
                                // GEOMETRY
                                c.geometry.crs?.let { string(1, it) }
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
                            struct(3) {
                                // ColumnMetaData
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
                                struct(12) {
                                    // Statistics
                                    i64(3, ch.nullCount)
                                    if (ch.max != null) binary(5, ch.max)
                                    if (ch.min != null) binary(6, ch.min)
                                    if (ch.max != null) bool(7, true)
                                    if (ch.min != null) bool(8, true)
                                    if (ch.column.type == ParquetType.FLOAT || ch.column.type == ParquetType.DOUBLE) i64(9, ch.nanCount)
                                }
                                val geo = ch.geo
                                if (geo != null) {
                                    struct(17) {
                                        // GeospatialStatistics
                                        val box = geo.bbox
                                        if (box != null) {
                                            struct(1) {
                                                double(1, box.xmin); double(2, box.xmax); double(3, box.ymin); double(4, box.ymax)
                                                box.zmin?.let { double(5, it) }; box.zmax?.let { double(6, it) }
                                                box.mmin?.let { double(7, it) }; box.mmax?.let { double(8, it) }
                                            }
                                        }
                                        listBegin(2, Type.I32, geo.geospatialTypes.size)
                                        geo.geospatialTypes.forEach { listI32(it) }
                                    }
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
            val kv = LinkedHashMap(keyValueMetadata)
            geoMetadata()?.let { kv.putIfAbsent(GeoParquetMetadata.KEY, it.toJson()) }
            if (kv.isNotEmpty()) {
                listBegin(5, Type.STRUCT, kv.size)
                for ((k, v) in kv) listStruct { string(1, k); string(2, v) }
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

    /**
     * The `geo` key for the file: the first GEOMETRY column is primary, `geometry_types` and `bbox` are the
     * union of the chunk statistics (the spec's names and `[xmin, ymin, (zmin, (mmin,)) xmax, …]` layout),
     * `crs` is omitted for OGC:CRS84 and restated as PROJJSON only when the column says CRS84 explicitly.
     */
    private fun geoMetadata(): GeoParquetMetadata? {
        val primary = columns.firstOrNull { it.geometry != null } ?: return null
        val idx = columns.indexOf(primary)
        val stats = groups.mapNotNull { it.chunks[idx].geo }
        val types = stats.flatMap { it.geospatialTypes }.distinct().sorted().map { code ->
            val base = listOf("Point", "LineString", "Polygon", "MultiPoint", "MultiLineString", "MultiPolygon", "GeometryCollection")[code % 1000 - 1]
            base + (Geometry.Dims.ofWkbCode(code)?.suffix ?: "")
        }
        val box = stats.mapNotNull { it.bbox }.let { if (it.isEmpty()) null else it.reduce { a, b -> a.union(b) } }
        val bbox = box?.let { b ->
            when {
                b.zmin != null && b.mmin != null -> listOf(b.xmin, b.ymin, b.zmin, b.mmin, b.xmax, b.ymax, b.zmax!!, b.mmax!!)
                b.zmin != null -> listOf(b.xmin, b.ymin, b.zmin, b.xmax, b.ymax, b.zmax!!)
                else -> listOf(b.xmin, b.ymin, b.xmax, b.ymax) // M without Z cannot be expressed in a geo bbox (spec)
            }
        }
        val crs = primary.geometry?.crs
        val meta = GeoParquetMetadata.forWriter(primary.name, types, bbox)
        if (crs == null || Crs.parse(crs) is Crs.LonLatWgs84) return meta
        // A non-WGS84 CRS given as an authority code: the geo key must carry inline PROJJSON, which this
        // writer cannot produce; the spec's advice is then to write the native type only.
        return null
    }

    companion object {
        val SUPPORTED_TYPES = setOf(ParquetType.INT32, ParquetType.INT64, ParquetType.FLOAT, ParquetType.DOUBLE, ParquetType.BYTE_ARRAY)
        private val MAGIC = "PAR1".toByteArray(Charsets.US_ASCII)
        private fun le32(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
        private fun le64(v: Long) = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array()
        private fun leF(v: Float) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(v).array()
        private fun leD(v: Double) = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(v).array()

        private fun unsignedCompare(a: ByteArray, b: ByteArray): Int {
            val n = minOf(a.size, b.size)
            for (i in 0 until n) {
                val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
                if (d != 0) return d
            }
            return a.size - b.size
        }
    }
}
