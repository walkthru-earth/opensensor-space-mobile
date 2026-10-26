package space.opensensor.discover

import space.opensensor.publicfeed.H3Grid
import space.opensensor.publicfeed.LatLng
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NearMeTargetTest {
    private class FakeGrid(override val available: Boolean = true, val cell: Long = 42L) : H3Grid {
        val asked = mutableListOf<Int>()

        override fun latLngToCell(lat: Double, lng: Double, res: Int): Long {
            asked += res
            return cell
        }

        override fun cellToLatLng(cell: Long): LatLng = LatLng(10.0, 20.0)

        override fun cellToBoundary(cell: Long): List<LatLng> = emptyList()

        override fun polygonToCells(ring: List<LatLng>, res: Int): List<Long> = emptyList()
    }

    @Test
    fun cameraTargetIsTheCoarseCellCentreNotTheFix() {
        val grid = FakeGrid()
        val target = nearMeTarget(grid, 10.0123, 20.0456)
        assertEquals(LatLng(10.0, 20.0), target)
        assertNotEquals(LatLng(10.0123, 20.0456), target)
        assertEquals(listOf(NEAR_ME_RES), grid.asked)
    }

    @Test
    fun resolutionStaysCoarse() {
        assertTrue(NEAR_ME_RES <= 6)
    }

    @Test
    fun noBindingOrNoCellLeavesTheMap() {
        assertNull(nearMeTarget(FakeGrid(available = false), 10.0, 20.0))
        assertNull(nearMeTarget(FakeGrid(cell = 0L), 10.0, 20.0))
    }
}
