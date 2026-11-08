package space.opensensor.map

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import space.opensensor.publicfeed.GeoBounds
import space.opensensor.publicfeed.H3Grid
import space.opensensor.publicfeed.H3Index
import space.opensensor.publicfeed.LatLng
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CellPickerGridTest {
    private val cell8 = "8828308281fffff"
    private val cell5 = "85283083fffffff"
    private val cell9 = "8928308280fffff"

    /** Every point is [cell8]. Its centre is a fixed point far from any camera in these tests. */
    private class FakeGrid(override val available: Boolean = true, val disk: Boolean = true) : H3Grid {
        val asked = mutableListOf<Int>()

        override fun latLngToCell(lat: Double, lng: Double, res: Int): Long {
            asked += res
            return H3Index.fromHex("8828308281fffff")
        }

        override fun cellToLatLng(cell: Long): LatLng = LatLng(37.77, -122.42)

        override fun cellToBoundary(cell: Long): List<LatLng> = emptyList()

        override fun gridDisk(cell: Long, k: Int): List<Long> = if (disk) List(k + 1) { cell } else super.gridDisk(cell, k)

        override fun polygonToCells(ring: List<LatLng>, res: Int): List<Long> = emptyList()
    }

    @Test
    fun aPickEmitsOnlyAResolution8CellId() {
        assertEquals(cell8, CellPickerGrid.pickable(cell8))
        assertNull(CellPickerGrid.pickable(cell5), "coarser cell")
        assertNull(CellPickerGrid.pickable(cell9), "finer cell")
        assertNull(CellPickerGrid.pickable("5.56,-0.20"), "a coordinate is no cell")
        assertNull(CellPickerGrid.pickable("8828308281FFFFF"), "not canonical")
        assertNull(CellPickerGrid.pickable(""))
        assertNull(CellPickerGrid.pickable(null))
    }

    @Test
    fun aTapReadsOnlyTheCellProperty() {
        val feature = buildJsonObject {
            put("h3", JsonPrimitive(cell8))
            put("lat", JsonPrimitive(5.56))
            put("lng", JsonPrimitive(-0.2))
        }
        assertEquals(cell8, CellPickerGrid.tapped(feature))
        assertNull(CellPickerGrid.tapped(buildJsonObject { put("lat", JsonPrimitive(5.56)); put("lng", JsonPrimitive(-0.2)) }))
        assertNull(CellPickerGrid.tapped(buildJsonObject { put("h3", JsonPrimitive("5.56,-0.20")) }))
        assertNull(CellPickerGrid.tapped(buildJsonObject { put("h3", JsonPrimitive(cell9)) }))
        assertNull(CellPickerGrid.tapped(null))
    }

    @Test
    fun theCentreCellIsResolution8AndOnlyWhenZoomedIn() {
        val grid = FakeGrid()
        assertNull(CellPickerGrid.centreCell(grid, CellPickerGrid.MIN_ZOOM - 0.1, 5.56, -0.2))
        assertTrue(grid.asked.isEmpty(), "no lookup while zoomed out")
        assertEquals(H3Index.fromHex(cell8), CellPickerGrid.centreCell(grid, CellPickerGrid.MIN_ZOOM, 5.56, -0.2))
        assertEquals(listOf(CellPickerGrid.RESOLUTION), grid.asked)
        assertEquals(8, CellPickerGrid.RESOLUTION)
        assertNull(CellPickerGrid.centreCell(FakeGrid(available = false), 15.0, 5.56, -0.2))
    }

    @Test
    fun theGridIsCellsAndFallsBackToTheCentre() {
        val bounds = GeoBounds(-0.21, 5.55, -0.19, 5.57)
        val rest = CameraRest(bounds, 14.0, 5.56, -0.2)
        val cells = CellPickerGrid.cellsAround(FakeGrid(), rest)
        assertEquals(CellPickerGrid.rings(bounds) + 1, cells.size)
        assertTrue(cells.all { H3Index.toHex(it) == cell8 })
        assertEquals(listOf(H3Index.fromHex(cell8)), CellPickerGrid.cellsAround(FakeGrid(disk = false), rest))
        assertEquals(emptyList(), CellPickerGrid.cellsAround(FakeGrid(), rest.copy(zoom = 3.0)))
    }

    @Test
    fun ringsStayBounded() {
        assertEquals(2, CellPickerGrid.rings(GeoBounds(0.0, 0.0, 0.0001, 0.0001)))
        assertEquals(CellPickerGrid.MAX_RINGS, CellPickerGrid.rings(GeoBounds(-10.0, -10.0, 10.0, 10.0)))
        // Across the antimeridian the span wraps, so it is small, not almost the whole world.
        assertTrue(CellPickerGrid.rings(GeoBounds(179.99, 0.0, -179.99, 0.01)) < CellPickerGrid.MAX_RINGS)
    }

    @Test
    fun theCameraGoesToTheCellCentreNeverAnotherPoint() {
        assertEquals(LatLng(37.77, -122.42), CellPickerGrid.centre(FakeGrid(), cell8))
        assertNull(CellPickerGrid.centre(FakeGrid(), "5.56,-0.20"))
        assertNull(CellPickerGrid.centre(FakeGrid(available = false), cell8))
        assertNull(CellPickerGrid.centre(FakeGrid(), null))
    }
}
