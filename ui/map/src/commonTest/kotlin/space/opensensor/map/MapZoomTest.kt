package space.opensensor.map

import kotlin.test.Test
import kotlin.test.assertEquals

class MapZoomTest {
    @Test
    fun zoomStepsStayInsideTheBasemapRange() {
        assertEquals(6.0, MapZoom.stepped(5.0, 1))
        assertEquals(4.0, MapZoom.stepped(5.0, -1))
        assertEquals(MapZoom.MAX, MapZoom.stepped(MapZoom.MAX, 1))
        assertEquals(MapZoom.MIN, MapZoom.stepped(MapZoom.MIN, -1))
        // A camera outside the range only moves the way pressed, never jumps back the other way.
        assertEquals(18.0, MapZoom.stepped(18.0, 1))
        assertEquals(17.0, MapZoom.stepped(18.0, -1))
        assertEquals(0.5, MapZoom.stepped(0.5, -1))
        assertEquals(1.5, MapZoom.stepped(0.5, 1))
        assertEquals(MapZoom.MAX, MapZoom.stepped(15.5, 1))
    }

    @Test
    fun quickTapsAddUpWhileTheCameraStillMoves() {
        val steps = ZoomSteps()
        val first = steps.next(5.0, 1)
        assertEquals(6.0, first.target)
        val second = steps.next(5.3, 1)
        assertEquals(7.0, second.target, "a tap during the ease steps from the last target")
        steps.settled(first.token)
        val third = steps.next(6.2, 1)
        assertEquals(8.0, third.target, "an older move ending keeps the newer target")
        steps.settled(third.token)
        assertEquals(4.0, steps.next(5.0, -1).target, "after the last move the camera leads again")
    }

    @Test
    fun anOlderMoveWithTheSameTargetKeepsTheNewerPress() {
        val steps = ZoomSteps()
        val inPress = steps.next(5.0, 1)
        val outPress = steps.next(5.5, -1)
        val again = steps.next(5.5, 1)
        assertEquals(inPress.target, again.target)
        steps.settled(inPress.token)
        steps.settled(outPress.token)
        assertEquals(7.0, steps.next(6.0, 1).target, "the newest press still leads after older moves with its target end")
    }
}
