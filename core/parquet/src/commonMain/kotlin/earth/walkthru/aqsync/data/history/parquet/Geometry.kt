package earth.walkthru.aqsync.data.history.parquet

/**
 * The seven OGC Simple Features geometry types, decoded from ISO WKB into flat `DoubleArray`s: no
 * per-vertex objects. Coordinates are interleaved `x y [z] [m]` in [dims] doubles per vertex, in the
 * order the file stores them (x = longitude/easting, y = latitude/northing — GeoParquet fixes that).
 *
 * This is the data model the map and the bbox filter need; it is *not* a geometry library (no
 * predicates, no reprojection).
 */
sealed class Geometry {
    /** Which coordinates a vertex has. */
    enum class Dims(val count: Int, val hasZ: Boolean, val hasM: Boolean, val wkbOffset: Int, val suffix: String) {
        XY(2, false, false, 0, ""), XYZ(3, true, false, 1000, " Z"), XYM(3, false, true, 2000, " M"), XYZM(4, true, true, 3000, " ZM");

        companion object {
            fun ofWkbCode(code: Int): Dims? = entries.firstOrNull { code / 1000 * 1000 == it.wkbOffset }
        }
    }

    abstract val dims: Dims

    /** ISO WKB type code as the `geospatial_types` statistics list it (1–7 plus the dimension offset). */
    abstract val wkbCode: Int

    /** The spec's `geometry_types` name (`"Point"`, `"Polygon Z"`, …). */
    val typeName: String get() = BASE_NAMES[wkbCode % 1000 - 1] + dims.suffix

    /** True when the geometry has no coordinates at all (`LINESTRING EMPTY`, a POINT of NaNs, …). */
    abstract val isEmpty: Boolean

    /** Visits every coordinate tuple (an array of [Dims.count] doubles) once. */
    abstract fun forEachVertex(visit: (DoubleArray, Int) -> Unit)

    /** Bounding box over every vertex, skipping NaN coordinates; null when empty. */
    fun bbox(): BoundingBox? {
        var xmin = Double.POSITIVE_INFINITY
        var xmax = Double.NEGATIVE_INFINITY
        var ymin = Double.POSITIVE_INFINITY
        var ymax = Double.NEGATIVE_INFINITY
        var zmin = Double.POSITIVE_INFINITY
        var zmax = Double.NEGATIVE_INFINITY
        var mmin = Double.POSITIVE_INFINITY
        var mmax = Double.NEGATIVE_INFINITY
        forEachVertex { c, i ->
            val x = c[i]
            val y = c[i + 1]
            if (!x.isNaN()) { if (x < xmin) xmin = x; if (x > xmax) xmax = x }
            if (!y.isNaN()) { if (y < ymin) ymin = y; if (y > ymax) ymax = y }
            if (dims.hasZ) { val z = c[i + 2]; if (!z.isNaN()) { if (z < zmin) zmin = z; if (z > zmax) zmax = z } }
            if (dims.hasM) { val m = c[i + if (dims.hasZ) 3 else 2]; if (!m.isNaN()) { if (m < mmin) mmin = m; if (m > mmax) mmax = m } }
        }
        if (xmin > xmax || ymin > ymax) return null
        return BoundingBox(
            xmin,
            xmax,
            ymin,
            ymax,
            zmin.takeIf { it <= zmax },
            zmax.takeIf { zmin <= it },
            mmin.takeIf { it <= mmax },
            mmax.takeIf { mmin <= it },
        )
    }

    /** A single vertex; an empty point is all NaN (the WKB convention). */
    class Point(val coords: DoubleArray, override val dims: Dims) : Geometry() {
        val x: Double get() = coords[0]
        val y: Double get() = coords[1]
        val z: Double? get() = if (dims.hasZ) coords[2] else null
        val m: Double? get() = if (dims.hasM) coords[if (dims.hasZ) 3 else 2] else null
        override val wkbCode: Int get() = 1 + dims.wkbOffset
        override val isEmpty: Boolean get() = coords.all { it.isNaN() }
        override fun forEachVertex(visit: (DoubleArray, Int) -> Unit) { if (!isEmpty) visit(coords, 0) }
    }

    /** [coords] holds `count * dims.count` doubles. */
    class LineString(val coords: DoubleArray, override val dims: Dims) : Geometry() {
        val count: Int get() = coords.size / dims.count
        override val wkbCode: Int get() = 2 + dims.wkbOffset
        override val isEmpty: Boolean get() = coords.isEmpty()
        override fun forEachVertex(visit: (DoubleArray, Int) -> Unit) { var i = 0; while (i < coords.size) { visit(coords, i); i += dims.count } }
    }

    /** Ring 0 is the exterior; each ring is a closed sequence of `count * dims.count` doubles. */
    class Polygon(val rings: List<DoubleArray>, override val dims: Dims) : Geometry() {
        override val wkbCode: Int get() = 3 + dims.wkbOffset
        override val isEmpty: Boolean get() = rings.all { it.isEmpty() }
        override fun forEachVertex(visit: (DoubleArray, Int) -> Unit) { for (r in rings) { var i = 0; while (i < r.size) { visit(r, i); i += dims.count } } }
    }

    class MultiPoint(val points: List<Point>, override val dims: Dims) : Geometry() {
        override val wkbCode: Int get() = 4 + dims.wkbOffset
        override val isEmpty: Boolean get() = points.all { it.isEmpty }
        override fun forEachVertex(visit: (DoubleArray, Int) -> Unit) { points.forEach { it.forEachVertex(visit) } }
    }

    class MultiLineString(val lines: List<LineString>, override val dims: Dims) : Geometry() {
        override val wkbCode: Int get() = 5 + dims.wkbOffset
        override val isEmpty: Boolean get() = lines.all { it.isEmpty }
        override fun forEachVertex(visit: (DoubleArray, Int) -> Unit) { lines.forEach { it.forEachVertex(visit) } }
    }

    class MultiPolygon(val polygons: List<Polygon>, override val dims: Dims) : Geometry() {
        override val wkbCode: Int get() = 6 + dims.wkbOffset
        override val isEmpty: Boolean get() = polygons.all { it.isEmpty }
        override fun forEachVertex(visit: (DoubleArray, Int) -> Unit) { polygons.forEach { it.forEachVertex(visit) } }
    }

    class GeometryCollection(val geometries: List<Geometry>, override val dims: Dims) : Geometry() {
        override val wkbCode: Int get() = 7 + dims.wkbOffset
        override val isEmpty: Boolean get() = geometries.all { it.isEmpty }
        override fun forEachVertex(visit: (DoubleArray, Int) -> Unit) { geometries.forEach { it.forEachVertex(visit) } }
    }

    /** WKT in the form DuckDB/PostGIS print (`POINT Z (1 2 3)`, `LINESTRING EMPTY`); used by tests and debug views. */
    fun toWkt(): String = buildString { appendWkt(this@Geometry, this) }

    companion object {
        private val BASE_NAMES = listOf("Point", "LineString", "Polygon", "MultiPoint", "MultiLineString", "MultiPolygon", "GeometryCollection")

        private fun appendWkt(g: Geometry, sb: StringBuilder) {
            sb.append(BASE_NAMES[g.wkbCode % 1000 - 1].uppercase())
            if (g.dims != Dims.XY) sb.append(g.dims.suffix)
            sb.append(' ')
            if (g.isEmpty) { sb.append("EMPTY"); return }
            when (g) {
                is Point -> { sb.append('('); appendCoords(g.coords, 0, g.dims, sb); sb.append(')') }
                is LineString -> appendSeq(g.coords, g.dims, sb)
                is Polygon -> { sb.append('('); g.rings.forEachIndexed { i, r -> if (i > 0) sb.append(", "); appendSeq(r, g.dims, sb) }; sb.append(')') }
                is MultiPoint -> { sb.append('('); g.points.forEachIndexed { i, p -> if (i > 0) sb.append(", "); appendCoords(p.coords, 0, g.dims, sb) }; sb.append(')') }
                is MultiLineString -> { sb.append('('); g.lines.forEachIndexed { i, l -> if (i > 0) sb.append(", "); appendSeq(l.coords, g.dims, sb) }; sb.append(')') }
                is MultiPolygon -> {
                    sb.append('(')
                    g.polygons.forEachIndexed { i, p -> if (i > 0) sb.append(", "); sb.append('('); p.rings.forEachIndexed { j, r -> if (j > 0) sb.append(", "); appendSeq(r, g.dims, sb) }; sb.append(')') }
                    sb.append(')')
                }
                is GeometryCollection -> { sb.append('('); g.geometries.forEachIndexed { i, c -> if (i > 0) sb.append(", "); appendWkt(c, sb) }; sb.append(')') }
            }
        }

        private fun appendSeq(coords: DoubleArray, dims: Dims, sb: StringBuilder) {
            sb.append('(')
            var i = 0
            while (i < coords.size) {
                if (i > 0) sb.append(", ")
                appendCoords(coords, i, dims, sb)
                i += dims.count
            }
            sb.append(')')
        }

        private fun appendCoords(coords: DoubleArray, at: Int, dims: Dims, sb: StringBuilder) {
            for (k in 0 until dims.count) {
                if (k > 0) sb.append(' ')
                val v = coords[at + k]
                if (v == kotlin.math.floor(v) && !v.isInfinite() && kotlin.math.abs(v) < 1e15) sb.append(v.toLong()) else sb.append(v)
            }
        }
    }
}

/**
 * ISO WKB decoder (OGC 06-103r4 / ISO 13249-3): byte-order flag per geometry, `uint32` type code with the
 * 1000/2000/3000 dimension offsets, then coordinates as IEEE doubles. EWKB's SRID flag (0x20000000) is
 * accepted and its SRID skipped so PostGIS exports read too. Anything else (curves, TINs, 2.5D via the
 * high-bit flags) raises [ParquetFormatException] naming the code.
 */
object Wkb {
    private const val EWKB_Z = 0x80000000.toInt()
    private const val EWKB_M = 0x40000000
    private const val EWKB_SRID = 0x20000000

    /** Decodes one geometry from `bytes[offset, offset + length)`; trailing bytes are ignored. */
    fun decode(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Geometry {
        if (offset < 0 || length < 0 || offset + length > bytes.size) throw ParquetFormatException("WKB window outside the buffer")
        return readGeometry(Reader(bytes, offset, offset + length), depth = 0)
    }

    /** Decodes every non-null row of a WKB column (no copies); null rows stay null. */
    fun decodeColumn(column: ColumnValues.Binary): List<Geometry?> =
        List(column.size) { i -> if (column.isNull(i)) null else decode(column.data, column.offsets[i], column.length(i)) }

    /** A cursor whose byte order changes per nested geometry, as WKB allows. */
    private class Reader(val data: ByteArray, var pos: Int, val limit: Int) {
        var bigEndian = false
        val remaining: Int get() = limit - pos

        fun byte(): Int {
            if (pos >= limit) throw ParquetFormatException("WKB truncated")
            return data[pos++].toInt()
        }

        fun int(): Int {
            if (remaining < 4) throw ParquetFormatException("WKB truncated")
            val v = if (bigEndian) {
                ((data[pos].toInt() and 0xFF) shl 24) or ((data[pos + 1].toInt() and 0xFF) shl 16) or
                    ((data[pos + 2].toInt() and 0xFF) shl 8) or (data[pos + 3].toInt() and 0xFF)
            } else {
                leInt(data, pos)
            }
            pos += 4
            return v
        }

        fun double(): Double {
            if (remaining < 8) throw ParquetFormatException("WKB truncated")
            val bits = if (bigEndian) {
                var b = 0L
                for (k in 0 until 8) b = (b shl 8) or (data[pos + k].toLong() and 0xFF)
                b
            } else {
                leLong(data, pos)
            }
            pos += 8
            return Double.fromBits(bits)
        }
    }

    private fun readGeometry(buf: Reader, depth: Int): Geometry {
        if (depth > 32) throw ParquetFormatException("WKB nested deeper than 32 levels")
        if (buf.remaining < 5) throw ParquetFormatException("WKB truncated (${buf.remaining} bytes)")
        buf.bigEndian = when (val flag = buf.byte()) {
            0 -> true
            1 -> false
            else -> throw ParquetFormatException("WKB byte-order flag $flag is invalid")
        }
        var code = buf.int()
        // EWKB flags (PostGIS): translate to the ISO offsets, skip the SRID.
        var ewkbDims = 0
        if (code and EWKB_Z != 0) { ewkbDims += 1000; code = code and EWKB_Z.inv() }
        if (code and EWKB_M != 0) { ewkbDims += 2000; code = code and EWKB_M.inv() }
        if (code and EWKB_SRID != 0) { code = code and EWKB_SRID.inv(); buf.int() }
        if (code < 0) throw ParquetFormatException("WKB type code ${code.toUInt()} is not supported")
        code += ewkbDims
        val base = code % 1000
        val dims = Geometry.Dims.ofWkbCode(code) ?: throw ParquetFormatException("WKB type code $code is not supported")
        if (base !in 1..7) throw ParquetFormatException("WKB type code $code is not a simple-features type")
        return when (base) {
            1 -> Geometry.Point(readCoords(buf, 1, dims), dims)
            2 -> Geometry.LineString(readCoords(buf, readCount(buf), dims), dims)
            3 -> Geometry.Polygon(List(readCount(buf)) { readCoords(buf, readCount(buf), dims) }, dims)
            4 -> Geometry.MultiPoint(List(readCount(buf)) { child(buf, depth, dims) as? Geometry.Point ?: throw ParquetFormatException("WKB MultiPoint holds a non-point") }, dims)
            5 -> Geometry.MultiLineString(List(readCount(buf)) { child(buf, depth, dims) as? Geometry.LineString ?: throw ParquetFormatException("WKB MultiLineString holds a non-linestring") }, dims)
            6 -> Geometry.MultiPolygon(List(readCount(buf)) { child(buf, depth, dims) as? Geometry.Polygon ?: throw ParquetFormatException("WKB MultiPolygon holds a non-polygon") }, dims)
            else -> Geometry.GeometryCollection(List(readCount(buf)) { child(buf, depth, dims) }, dims)
        }
    }

    /** A nested geometry re-reads its own byte order; its dimensions must match the parent's. */
    private fun child(buf: Reader, depth: Int, dims: Geometry.Dims): Geometry {
        val g = readGeometry(buf, depth + 1)
        if (g.dims != dims) throw ParquetFormatException("WKB child has ${g.dims} coordinates inside a $dims geometry")
        return g
    }

    private fun readCount(buf: Reader): Int {
        if (buf.remaining < 4) throw ParquetFormatException("WKB truncated")
        val n = buf.int()
        if (n < 0 || n > buf.remaining) throw ParquetFormatException("WKB count $n exceeds the data")
        return n
    }

    private fun readCoords(buf: Reader, count: Int, dims: Geometry.Dims): DoubleArray {
        val doubles = count * dims.count
        if (doubles.toLong() * 8 > buf.remaining) throw ParquetFormatException("WKB truncated: $count vertices of ${dims.count} doubles do not fit ${buf.remaining} bytes")
        val out = DoubleArray(doubles)
        for (i in 0 until doubles) out[i] = buf.double()
        return out
    }
}
