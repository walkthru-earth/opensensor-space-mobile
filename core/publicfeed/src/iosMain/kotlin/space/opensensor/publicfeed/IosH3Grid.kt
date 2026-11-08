package space.opensensor.publicfeed

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.value
import space.opensensor.publicfeed.h3c.CellBoundary
import space.opensensor.publicfeed.h3c.GeoPolygon
import space.opensensor.publicfeed.h3c.H3IndexVar
import space.opensensor.publicfeed.h3c.LinkedGeoPolygon
import space.opensensor.publicfeed.h3c.cellsToLinkedMultiPolygon
import space.opensensor.publicfeed.h3c.destroyLinkedMultiPolygon
import space.opensensor.publicfeed.h3c.maxGridDiskSize
import space.opensensor.publicfeed.h3c.maxPolygonToCellsSize
import kotlin.math.PI
import space.opensensor.publicfeed.h3c.LatLng as CLatLng
import space.opensensor.publicfeed.h3c.cellToBoundary as cCellToBoundary
import space.opensensor.publicfeed.h3c.cellToLatLng as cCellToLatLng
import space.opensensor.publicfeed.h3c.gridDisk as cGridDisk
import space.opensensor.publicfeed.h3c.isValidCell as cIsValidCell
import space.opensensor.publicfeed.h3c.latLngToCell as cLatLngToCell
import space.opensensor.publicfeed.h3c.polygonToCells as cPolygonToCells

/**
 * H3 through the vendored H3 C library v4.5.0 (`src/nativeInterop/h3c`), bound with cinterop.
 * `com.uber:h3` 4.5.1 on Android gives the same indexes and matches its polygons within 1e-9 degrees.
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

    override fun gridDisk(cell: Long, k: Int): List<Long> = memScoped {
        require(k >= 0) { "grid disk k must not be negative" }
        val size = alloc<LongVar>()
        check(maxGridDiskSize(k, size.ptr) == 0u) { "maxGridDiskSize failed for $k" }
        val n = size.value.toInt()
        val out = allocArray<H3IndexVar>(n)
        for (i in 0 until n) out[i] = 0u
        check(cGridDisk(cell.toULong(), k, out) == 0u) { "gridDisk failed for ${H3Index.toHex(cell)}" }
        // Near a pentagon the disk holds fewer cells than the maximum, and the rest stay 0.
        buildList { for (i in 0 until n) if (out[i] != 0uL) add(out[i].toLong()) }
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

    override fun isValidCell(cell: Long): Boolean = cIsValidCell(cell.toULong()) != 0

    override fun cellsToMultiPolygon(cells: Collection<Long>): List<List<List<LatLng>>> {
        if (cells.isEmpty()) return emptyList()
        return memScoped {
            val set = allocArray<H3IndexVar>(cells.size)
            cells.forEachIndexed { i, cell -> set[i] = cell.toULong() }
            // The root polygon is ours, the C library allocates every node it links from it.
            val root = alloc<LinkedGeoPolygon>()
            check(cellsToLinkedMultiPolygon(set, cells.size, root.ptr) == 0u) { "cellsToLinkedMultiPolygon failed" }
            try {
                buildList {
                    var polygon: LinkedGeoPolygon? = root
                    while (polygon != null) {
                        val loops = buildList {
                            var loop = polygon.first?.pointed
                            while (loop != null) {
                                val ring = buildList {
                                    var node = loop.first?.pointed
                                    while (node != null) {
                                        add(LatLng(node.vertex.lat * TO_DEG, node.vertex.lng * TO_DEG))
                                        node = node.next?.pointed
                                    }
                                }
                                if (ring.isNotEmpty()) add(ring)
                                loop = loop.next?.pointed
                            }
                        }
                        if (loops.isNotEmpty()) add(loops)
                        polygon = polygon.next?.pointed
                    }
                }
            } finally {
                destroyLinkedMultiPolygon(root.ptr)
            }
        }
    }
}

actual fun platformH3Grid(): H3Grid = NativeH3Grid
