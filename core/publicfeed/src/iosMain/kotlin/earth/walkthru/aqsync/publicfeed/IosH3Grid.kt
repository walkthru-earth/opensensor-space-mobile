package earth.walkthru.aqsync.publicfeed

import earth.walkthru.aqsync.publicfeed.h3c.CellBoundary
import earth.walkthru.aqsync.publicfeed.h3c.GeoPolygon
import earth.walkthru.aqsync.publicfeed.h3c.H3IndexVar
import earth.walkthru.aqsync.publicfeed.h3c.maxPolygonToCellsSize
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.value
import kotlin.math.PI
import earth.walkthru.aqsync.publicfeed.h3c.LatLng as CLatLng
import earth.walkthru.aqsync.publicfeed.h3c.cellToBoundary as cCellToBoundary
import earth.walkthru.aqsync.publicfeed.h3c.cellToLatLng as cCellToLatLng
import earth.walkthru.aqsync.publicfeed.h3c.latLngToCell as cLatLngToCell
import earth.walkthru.aqsync.publicfeed.h3c.polygonToCells as cPolygonToCells

/**
 * H3 through the vendored H3 C library v4.5.0 (`src/nativeInterop/h3c`), bound with cinterop. The
 * same C version `com.uber:h3` 4.5.0 wraps on Android, so both phones agree on every index.
 * The C API works in radians, this interface in degrees.
 */
@OptIn(ExperimentalForeignApi::class)
internal object NativeH3Grid : H3Grid {
    private const val TO_RAD = PI / 180.0
    private const val TO_DEG = 180.0 / PI

    override fun latLngToCell(lat: Double, lng: Double, res: Int): Long = memScoped {
        val g = alloc<CLatLng>()
        g.lat = lat * TO_RAD
        g.lng = lng * TO_RAD
        val out = alloc<H3IndexVar>()
        check(cLatLngToCell(g.ptr, res, out.ptr) == 0u) { "latLngToCell failed for $lat, $lng at $res" }
        out.value.toLong()
    }

    override fun cellToLatLng(cell: Long): LatLng = memScoped {
        val g = alloc<CLatLng>()
        check(cCellToLatLng(cell.toULong(), g.ptr) == 0u) { "cellToLatLng failed for ${H3Index.toHex(cell)}" }
        LatLng(g.lat * TO_DEG, g.lng * TO_DEG)
    }

    override fun cellToBoundary(cell: Long): List<LatLng> = memScoped {
        val b = alloc<CellBoundary>()
        check(cCellToBoundary(cell.toULong(), b.ptr) == 0u) { "cellToBoundary failed for ${H3Index.toHex(cell)}" }
        List(b.numVerts) { i -> LatLng(b.verts[i].lat * TO_DEG, b.verts[i].lng * TO_DEG) }
    }

    override fun polygonToCells(ring: List<LatLng>, res: Int): List<Long> = memScoped {
        val verts = allocArray<CLatLng>(ring.size)
        ring.forEachIndexed { i, p ->
            verts[i].lat = p.lat * TO_RAD
            verts[i].lng = p.lng * TO_RAD
        }
        val polygon = alloc<GeoPolygon>()
        polygon.geoloop.numVerts = ring.size
        polygon.geoloop.verts = verts
        polygon.numHoles = 0
        polygon.holes = null
        val size = alloc<LongVar>()
        check(maxPolygonToCellsSize(polygon.ptr, res, 0u, size.ptr) == 0u) { "maxPolygonToCellsSize failed" }
        val n = size.value.toInt()
        if (n == 0) return@memScoped emptyList()
        val out = allocArray<H3IndexVar>(n)
        for (i in 0 until n) out[i] = 0u
        check(cPolygonToCells(polygon.ptr, res, 0u, out) == 0u) { "polygonToCells failed" }
        buildList { for (i in 0 until n) if (out[i] != 0uL) add(out[i].toLong()) }
    }
}

actual fun platformH3Grid(): H3Grid = NativeH3Grid
