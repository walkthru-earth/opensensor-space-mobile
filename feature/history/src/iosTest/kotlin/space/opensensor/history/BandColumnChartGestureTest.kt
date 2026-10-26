package space.opensensor.history

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlinx.datetime.TimeZone
import space.opensensor.data.history.HistoryColumn
import space.opensensor.data.history.HistoryRange
import space.opensensor.data.history.Metrics
import space.opensensor.settings.DetailLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A vertical swipe over the History chart scrolls the page around it, a sideways drag scrubs the
 * bars, and a tap picks one (#119). The same chart sits in History and in Files. Rendered headless on
 * the iOS simulator (verifyIos).
 */
@OptIn(ExperimentalTestApi::class)
class BandColumnChartGestureTest {
    private val day = (0 until 24).map { i ->
        HistoryColumn(startS = i * HOUR, endS = (i + 1) * HOUR, mean = 10f + i, max = 10f + i, coverage = 1f, estimatedShare = 0f)
    }

    private class Recorded(val scroll: ScrollState, val picks: MutableList<HistoryColumn?>)

    private fun ComposeUiTest.chartInAScrollingPage(): Recorded {
        val picks = ArrayList<HistoryColumn?>()
        val scroll = ScrollState(0)
        setContent {
            MaterialTheme {
                Column(Modifier.width(320.dp).height(480.dp).verticalScroll(scroll)) {
                    Spacer(Modifier.height(80.dp))
                    BandColumnChart(
                        day,
                        Metrics.PM25,
                        HistoryRange.DAY,
                        0,
                        24 * HOUR,
                        selected = null,
                        onSelect = { picks += it },
                        modifier = Modifier.testTag(CHART),
                        zone = TimeZone.UTC,
                        level = DetailLevel.SIMPLE,
                    )
                    Spacer(Modifier.height(1200.dp))
                }
            }
        }
        return Recorded(scroll, picks)
    }

    @Test
    fun aVerticalSwipeOverTheChartScrollsThePage() = runComposeUiTest {
        val r = chartInAScrollingPage()
        onNodeWithTag(CHART).performTouchInput { swipeUp() }
        waitForIdle()
        assertTrue(r.scroll.value > 0, "the page did not scroll")
        assertTrue(r.picks.isEmpty(), "a vertical swipe picked a bar: ${r.picks}")
    }

    @Test
    fun aSidewaysDragScrubsAndLeavesThePageStill() = runComposeUiTest {
        val r = chartInAScrollingPage()
        onNodeWithTag(CHART).performTouchInput { swipeLeft(startX = right - 4f, endX = centerX) }
        waitForIdle()
        assertEquals(0, r.scroll.value)
        assertTrue(r.picks.size > 1, "a drag picked ${r.picks.size} bars")
        assertNotNull(r.picks.last(), "the drag ended off the bars")
    }

    @Test
    fun aTapPicksTheBarUnderIt() = runComposeUiTest {
        val r = chartInAScrollingPage()
        onNodeWithTag(CHART).performTouchInput { click(centerRight.copy(x = centerRight.x - 4f)) }
        waitForIdle()
        assertEquals(0, r.scroll.value)
        assertEquals(day.last(), r.picks.single())
    }

    private companion object {
        const val CHART = "chart"
        const val HOUR = 3_600L
    }
}
