package space.opensensor.discover

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Dp
import space.opensensor.designsystem.MinTouchTarget
import space.opensensor.publicfeed.FeedWindow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The floating map controls work for one tap and for screen readers (#116). The time window pill
 * is a button that stays open until a window is picked, zoom has two named buttons, and the sheet
 * header is a button that says what it does. Every target is at least 48 dp. Rendered headless on
 * the iOS simulator (verifyIos).
 */
@OptIn(ExperimentalTestApi::class)
class MapControlsSemanticsTest {
    private fun SemanticsNodeInteraction.size(): Pair<Dp, Dp> = getBoundsInRoot().let { (it.right - it.left) to (it.bottom - it.top) }

    private fun SemanticsNodeInteraction.assertTarget() {
        val (w, h) = size()
        assertTrue(w >= MinTouchTarget && h >= MinTouchTarget, "target $w x $h is under $MinTouchTarget")
    }

    private fun SemanticsNodeInteraction.role(): Role? = fetchSemanticsNode().config.getOrElseNullable(SemanticsProperties.Role) { null }

    private fun SemanticsNodeInteraction.state(): String? = fetchSemanticsNode().config.getOrElseNullable(SemanticsProperties.StateDescription) { null }

    @Test
    fun theWindowSliderIsAButtonThatStaysOpenUntilAWindowIsPicked() = runComposeUiTest {
        var window by mutableStateOf(FeedWindow.DAY)
        val windows = FeedWindow.entries
        setContent {
            MaterialTheme {
                WindowSlider(window, windows, discoverStrings(), onWindow = { window = it })
            }
        }
        val slider = onNodeWithContentDescription("Time window")
        assertEquals(Role.Button, slider.role())
        slider.assertHasClickAction()
        assertEquals("24 hours", slider.state())
        slider.assertTarget()
        val folded = slider.size().second

        slider.performSemanticsAction(SemanticsActions.OnClick)
        // Long after the old auto collapse would have fired, the stops are still open.
        mainClock.advanceTimeBy(10_000)
        waitForIdle()
        val open = slider.size().second
        assertTrue(open >= MinTouchTarget * windows.size, "open slider $open is under one 48 dp stop per window")
        assertTrue(open > folded, "slider did not grow")

        // A screen reader action picks a window and folds the pill.
        val pickWeek = slider.fetchSemanticsNode().config[SemanticsActions.CustomActions].first { it.label == "7 days" }
        runOnIdle { pickWeek.action() }
        mainClock.advanceTimeBy(2_000)
        waitForIdle()
        assertEquals(FeedWindow.WEEK, window)
        assertEquals(folded, slider.size().second)
    }

    @Test
    fun zoomHasTwoNamedButtonsThatActOnOneTap() = runComposeUiTest {
        var zoomIns = 0
        var zoomOuts = 0
        setContent {
            MaterialTheme {
                val s = discoverStrings()
                ZoomPair(s.zoomIn, s.zoomOut, onZoomIn = { zoomIns++ }, onZoomOut = { zoomOuts++ })
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

    @Test
    fun theSheetHeaderIsAButtonThatSaysWhatItDoes() = runComposeUiTest {
        var open by mutableStateOf(false)
        setContent {
            MaterialTheme {
                SheetHandle(open = open, strings = discoverStrings(), onToggle = { open = !open })
            }
        }
        val more = onNodeWithContentDescription("Show more")
        assertEquals(Role.Button, more.role())
        more.assertTarget()
        more.performClick()
        waitForIdle()
        assertTrue(open)
        onNodeWithContentDescription("Show less").performClick()
        waitForIdle()
        assertTrue(!open)
    }
}
