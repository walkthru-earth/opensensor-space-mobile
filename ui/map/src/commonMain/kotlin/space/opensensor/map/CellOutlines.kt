package space.opensensor.map

import space.opensensor.publicfeed.CellGeoJson
import space.opensensor.publicfeed.H3Grid
import space.opensensor.publicfeed.H3Index

/**
 * Plain H3 cell outlines as one GeoJSON FeatureCollection, for a map that shows the grid itself
 * rather than measurements. Each cell is a Polygon with properties `h3` (hex) and `selected`, so a
 * caller fills the chosen cell with a filter on `selected`. The rings are the ones Discover draws
 * ([CellGeoJson.ring]), continuous across the antimeridian. A cell with no boundary is left out.
 */
object CellOutlines {
    fun geoJson(h3: H3Grid, cells: Iterable<Long>, selected: Long? = null): String {
        val sb = StringBuilder(256)
        sb.append("{\"type\":\"FeatureCollection\",\"features\":[")
        var first = true
        for (cell in cells.distinct()) {
            val ring = CellGeoJson.ring(h3, cell)
            if (ring.isEmpty()) continue
            if (!first) sb.append(',')
            first = false
            sb.append("{\"type\":\"Feature\",\"properties\":{\"h3\":\"").append(H3Index.toHex(cell)).append('"')
            sb.append(",\"selected\":").append(cell == selected)
            sb.append("},\"geometry\":")
            CellGeoJson.appendPolygon(sb, ring)
            sb.append('}')
        }
        sb.append("]}")
        return sb.toString()
    }
}
