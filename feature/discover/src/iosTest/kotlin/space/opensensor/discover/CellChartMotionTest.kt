package space.opensensor.discover

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import space.opensensor.designsystem.LocalReduceMotion
import space.opensensor.publicfeed.FeedProperty
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * With Reduce Motion the area's marker on the spread chart and the mean on a range row jump to a new
 * value, and without it they slide (#114). Rendered headless on the iOS simulator (verifyIos).
 */
@OptIn(ExperimentalTestApi::class)
class CellChartMotionTest {
    private val pm25 = FeedProperty("pm25", "PM2.5", "µg/m³", "Air")
    private val values = (0..100).map { it.toDouble() }
    private val domain = ChartDomain.forScale(null, values)!!

    /** Where the marker is drawn two frames after its value moves from [FROM] to [TO], and its end. */
    private fun drawnTwoFramesIn(reduceMotion: Boolean, chart: @Composable (Double) -> Unit): Float {
        var drawn = Float.NaN
        runComposeUiTest {
            mainClock.autoAdvance = false
            val value = mutableStateOf(FROM)
            setContent {
                CompositionLocalProvider(LocalReduceMotion provides reduceMotion, LocalMarkerDrawRecorder provides { drawn = it }) {
                    MaterialTheme { Box(Modifier.width(320.dp)) { chart(value.value) } }
                }
            }
            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeByFrame()
            assertEquals(domain.fraction(FROM), drawn, 1e-4f, "the marker starts at its first value")
            value.value = TO
            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeByFrame()
        }
        return drawn
    }

    private fun assertJumpsOnlyWithReduceMotion(chart: @Composable (Double) -> Unit) {
        val end = domain.fraction(TO)
        assertEquals(end, drawnTwoFramesIn(reduceMotion = true, chart), 1e-4f, "the marker still slides with reduce motion")
        // Guards the line above, without reduce motion the tween is still on its way two frames in.
        val sliding = drawnTwoFramesIn(reduceMotion = false, chart)
        assertTrue(sliding < end - 0.05f, "the marker jumped to $sliding without reduce motion")
    }

    @Test
    fun theSpreadChartsMarkerJumpsOnlyWithReduceMotion() = assertJumpsOnlyWithReduceMotion { here ->
        SpreadChart(values, pm25, scale = null, strings = discoverStrings(), here = here)
    }

    @Test
    fun theRangeRowsMeanJumpsOnlyWithReduceMotion() = assertJumpsOnlyWithReduceMotion { mean ->
        RangeRow(MetricRange(pm25, mean, min = null, max = null, domain = domain, color = 0xFF3366CC.toInt()), discoverStrings(), onClick = {})
    }

    private companion object {
        const val FROM = 20.0
        const val TO = 80.0
    }
}
