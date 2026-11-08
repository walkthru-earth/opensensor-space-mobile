package space.opensensor.map

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import space.opensensor.designsystem.MinTouchTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Zoom has two named buttons that act on one tap, each at least 48 dp (#116). Rendered headless on
 * the iOS simulator (verifyIos).
 */
@OptIn(ExperimentalTestApi::class)
class ZoomControlsSemanticsTest {
    private fun SemanticsNodeInteraction.assertTarget() {
        val b = getBoundsInRoot()
        val (w, h) = (b.right - b.left) to (b.bottom - b.top)
        assertTrue(w >= MinTouchTarget && h >= MinTouchTarget, "target $w x $h is under $MinTouchTarget")
    }

    private fun SemanticsNodeInteraction.role(): Role? = fetchSemanticsNode().config.getOrElseNullable(SemanticsProperties.Role) { null }

    @Test
    fun zoomHasTwoNamedButtonsThatActOnOneTap() = runComposeUiTest {
        var zoomIns = 0
        var zoomOuts = 0
        setContent {
            MaterialTheme {
                ZoomPair(onZoomIn = { zoomIns++ }, onZoomOut = { zoomOuts++ })
            }
        }
        val zoomIn = onNodeWithContentDescription("Zoom in")
        val zoomOut = onNodeWithContentDescription("Zoom out")
        for (button in listOf(zoomIn, zoomOut)) {
            assertEquals(Role.Button, button.role())
            button.assertTarget()
        }
        zoomIn.performClick()
        zoomOut.performClick()
        zoomOut.performClick()
        waitForIdle()
        assertEquals(1, zoomIns)
        assertEquals(2, zoomOuts)
    }
}
