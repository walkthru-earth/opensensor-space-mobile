package space.opensensor.map

import space.opensensor.publicfeed.H3Grid
import space.opensensor.publicfeed.H3Index
import space.opensensor.publicfeed.LatLng
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CellOutlinesTest {
    /** A grid where cell n is a small square at longitude n, and cell 0 has no boundary. */
    private val grid = object : H3Grid {
        override fun latLngToCell(lat: Double, lng: Double, res: Int): Long = error("unused")

        override fun cellToLatLng(cell: Long): LatLng = error("unused")

        override fun cellToBoundary(cell: Long): List<LatLng> = if (cell == 0L) {
            emptyList()
        } else {
            val x = cell.toDouble()
            listOf(LatLng(0.0, x), LatLng(0.0, x + 0.5), LatLng(0.5, x + 0.5), LatLng(0.5, x))
        }

        override fun polygonToCells(ring: List<LatLng>, res: Int): List<Long> = error("unused")
    }

    @Test
    fun eachCellIsOneClosedPolygonWithItsHexAndSelection() {
        val json = CellOutlines.geoJson(grid, listOf(1L, 2L, 1L), selected = 2L)
        val one = H3Index.toHex(1L)
        val two = H3Index.toHex(2L)
        assertEquals(
            "{\"type\":\"FeatureCollection\",\"features\":[" +
                "{\"type\":\"Feature\",\"properties\":{\"h3\":\"$one\",\"selected\":false},\"geometry\":" +
                "{\"type\":\"Polygon\",\"coordinates\":[[[1.0,0.0],[1.5,0.0],[1.5,0.5],[1.0,0.5],[1.0,0.0]]]}}," +
                "{\"type\":\"Feature\",\"properties\":{\"h3\":\"$two\",\"selected\":true},\"geometry\":" +
                "{\"type\":\"Polygon\",\"coordinates\":[[[2.0,0.0],[2.5,0.0],[2.5,0.5],[2.0,0.5],[2.0,0.0]]]}}" +
                "]}",
            json,
        )
    }

    @Test
    fun aCellWithNoBoundaryIsLeftOut() {
        val json = CellOutlines.geoJson(grid, listOf(0L, 3L))
        assertEquals(1, Regex("\"Feature\"").findAll(json).count())
        assertTrue("\"h3\":\"${H3Index.toHex(3L)}\"" in json)
        assertEquals("{\"type\":\"FeatureCollection\",\"features\":[]}", CellOutlines.geoJson(grid, emptyList()))
    }
}
