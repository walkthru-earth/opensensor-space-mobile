package space.opensensor.map

import space.opensensor.publicfeed.GeoBounds
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** When [BaseMap] fits its area, once for a map the person moves and on each new area for a still one. */
class MapFitTest {
    private val town = GeoBounds(west = -0.25, south = 5.55, east = -0.15, north = 5.65)
    private val wider = GeoBounds(west = -0.35, south = 5.55, east = -0.15, north = 5.70)

    @Test
    fun aMapThePersonMovesFitsOnceAndKeepsTheirView() {
        assertTrue(MapFit.due(town, movable = true, fitted = false, fittedTo = null))
        assertFalse(MapFit.due(town, movable = true, fitted = true, fittedTo = town))
        assertFalse(MapFit.due(wider, movable = true, fitted = true, fittedTo = town))
        // After a rotation the area is forgotten, the fit is not.
        assertFalse(MapFit.due(wider, movable = true, fitted = true, fittedTo = null))
    }

    @Test
    fun aStillMapFitsEachNewAreaAndOnlyThen() {
        assertTrue(MapFit.due(town, movable = false, fitted = false, fittedTo = null))
        assertFalse(MapFit.due(town, movable = false, fitted = true, fittedTo = town))
        assertTrue(MapFit.due(wider, movable = false, fitted = true, fittedTo = town))
        assertTrue(MapFit.due(town, movable = false, fitted = true, fittedTo = null))
    }
}
