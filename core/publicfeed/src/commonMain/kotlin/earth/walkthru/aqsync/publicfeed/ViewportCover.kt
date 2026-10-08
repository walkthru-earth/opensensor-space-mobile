package earth.walkthru.aqsync.publicfeed

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** A map viewport in degrees. West may exceed east when it crosses the antimeridian. */
data class GeoBounds(val west: Double, val south: Double, val east: Double, val north: Double)

/**
 * The cells to read for one viewport. [ranges] are merged INT64 `h3_index` ranges at
 * [dataRes]. Null ranges mean the whole world is in view and no spatial filter applies.
 */
data class Cover(val dataRes: Int, val coverRes: Int, val coverCells: Int, val ranges: List<LongRange>?)

/**
 * Viewport to H3 ranges, as the website `/indices` explorer does (`h3-viewport.ts`).
 *
 * 1. Resolution is `round(zoom * 0.8)`, clamped to 2..7. Deeper zoom keeps showing 7, the finest
 *    published resolution (privacy rule 2).
 * 2. The viewport, widened by one cell edge so cells cut by the screen edge are kept, is covered
 *    at the data resolution, coarsening until the cover holds at most [MAX_CELLS] cells.
 * 3. Each cover cell becomes the range of its descendants at the data resolution.
 */
object ViewportCover {
    const val MAX_CELLS = 1024
    const val MIN_RES = 2
    const val MAX_RES = 7

    /** Average cell area in km² by resolution, from the H3 resolution table. */
    private val AREA_KM2 = doubleArrayOf(4_357_449.0, 609_788.0, 86_801.8, 12_393.4, 1_770.3, 252.9, 36.13, 5.16)

    /** Average edge length in km by resolution. */
    private val EDGE_KM = doubleArrayOf(1281.3, 483.1, 182.5, 68.98, 26.07, 9.85, 3.72, 1.41)

    fun resolutionForZoom(zoom: Double): Int = (zoom * 0.8).roundToInt().coerceIn(MIN_RES, MAX_RES)

    fun cover(h3: H3Grid, bounds: GeoBounds, dataRes: Int): Cover {
        require(dataRes in MIN_RES..MAX_RES) { "data resolution $dataRes outside $MIN_RES..$MAX_RES" }
        val boxes = normalise(bounds) ?: return Cover(dataRes, dataRes, 0, null)
        var res = dataRes
        // Skip resolutions whose estimated cover is far over budget, so a wide view never asks H3
        // for tens of thousands of cells only to throw them away.
        while (res > 0 && estimate(boxes, res) > MAX_CELLS * 4) res--
        while (true) {
            val margin = EDGE_KM[res.coerceAtMost(EDGE_KM.lastIndex)] / 111.0
            val cells = LinkedHashSet<Long>()
            for (box in boxes) {
                val wide = box.widened(margin)
                cells += h3.polygonToCells(wide.ring(), res)
                // A box smaller than one cell may contain no cell centre. Its corners and centre
                // then name the cells it touches.
                for (p in wide.ring() + LatLng((wide.south + wide.north) / 2, (wide.west + wide.east) / 2)) {
                    cells += h3.latLngToCell(p.lat, p.lng, res)
                }
            }
            if (cells.size <= MAX_CELLS || res == 0) {
                val ranges = H3Index.merge(cells.map { H3Index.descendantRange(it, dataRes) })
                return Cover(dataRes, res, cells.size, ranges)
            }
            res--
        }
    }

    /**
     * Splits the viewport into boxes inside -180..180 that are each under 180° wide (H3 polygon
     * edges must be shorter than 180°). Null means the view spans the whole world.
     */
    internal fun normalise(bounds: GeoBounds): List<GeoBounds>? {
        val south = bounds.south.coerceIn(-85.0, 85.0)
        val north = bounds.north.coerceIn(-85.0, 85.0)
        if (north <= south) return emptyList()
        var span = bounds.east - bounds.west
        if (span < 0) span += 360.0
        if (span >= 180.0) return null
        val west = wrap(bounds.west)
        val east = west + span
        val pieces = if (east <= 180.0) {
            listOf(west to east)
        } else {
            listOf(west to 180.0, -180.0 to east - 360.0)
        }
        return pieces.flatMap { (w, e) ->
            // Halve anything wider than 90° so each polygon stays well under the 180° limit.
            if (e - w > 90.0) listOf(w to (w + e) / 2, (w + e) / 2 to e) else listOf(w to e)
        }.map { (w, e) -> GeoBounds(w, south, e, north) }
    }

    private fun wrap(lng: Double): Double {
        val x = lng - 360.0 * floor((lng + 180.0) / 360.0)
        return if (x >= 180.0) x - 360.0 else x
    }

    private fun estimate(boxes: List<GeoBounds>, res: Int): Double = boxes.sumOf { areaKm2(it) } / AREA_KM2[min(res, AREA_KM2.lastIndex)]

    private fun areaKm2(b: GeoBounds): Double {
        val r = 6371.0
        val dLng = abs(b.east - b.west) * PI / 180.0
        return r * r * dLng * abs(sin(b.north * PI / 180.0) - sin(b.south * PI / 180.0))
    }

    private fun GeoBounds.widened(marginDeg: Double) = GeoBounds(
        west = max(-180.0, west - marginDeg),
        south = max(-89.0, south - marginDeg),
        east = min(180.0, east + marginDeg),
        north = min(89.0, north + marginDeg),
    )

    private fun GeoBounds.ring(): List<LatLng> = listOf(
        LatLng(south, west),
        LatLng(south, east),
        LatLng(north, east),
        LatLng(north, west),
    )
}
