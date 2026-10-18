package earth.walkthru.opensensor.history

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import earth.walkthru.aqsync.data.history.ChartAxes
import earth.walkthru.aqsync.data.history.HistoryColumn
import earth.walkthru.aqsync.data.history.HistoryRange
import earth.walkthru.aqsync.data.history.MetricSpec
import earth.walkthru.aqsync.data.history.Metrics
import earth.walkthru.aqsync.settings.DetailLevel
import earth.walkthru.aqsync.units.UnitSystem
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.resources.stringResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * At a font size of 200 percent (fontScale 2.0) no axis label of the History bar chart is clipped or
 * drawn over another (#99), on a 320 dp phone. The labels are measured with the chart's own text
 * style, so this is the size TalkBack and VoiceOver users with large text see.
 */
@OptIn(ExperimentalTestApi::class)
class BandColumnChartFontScaleTest {
    private class Case(val name: String, val metric: MetricSpec, val range: HistoryRange, val spanS: Long, val peak: Float, val system: UnitSystem = UnitSystem.METRIC)

    private val cases = listOf(
        Case("PM2.5 day", Metrics.PM25, HistoryRange.DAY, DAY, 40f),
        Case("PM2.5 week, off scale", Metrics.PM25, HistoryRange.WEEK, 7 * DAY, 180f),
        Case("PM10 month", Metrics.PM10, HistoryRange.MONTH, 30 * DAY, 60f),
        Case("particle count year", Metrics.PARTICLES_GT03, HistoryRange.YEAR, 365 * DAY, 12_000f),
        Case("temperature in °F", Metrics.AMBIENT_TEMP, HistoryRange.DAY, DAY, 38f, UnitSystem.IMPERIAL),
    )

    private fun columns(case: Case): List<HistoryColumn> {
        val n = 24
        val slot = case.spanS / n
        return (0 until n).map { i ->
            val mean = case.peak * (i + 1) / n
            HistoryColumn(startS = i * slot, endS = (i + 1) * slot, mean = mean, max = mean, coverage = 1f, estimatedShare = 0f)
        }
    }

    /** Every label laid out at [fontScale] on a [widthDp] by 200 dp chart, as `case: problem` lines, empty when all fit. */
    @Composable
    private fun problems(fontScale: Float, widthDp: Int): List<String> {
        val base = LocalDensity.current
        var out = emptyList<String>()
        val guideText = stringResource(guidelineLabelRes(DetailLevel.DETAILED))
        CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
            val density = LocalDensity.current
            val measurer = rememberTextMeasurer()
            val label = chartTextStyle(Color.Black, AXIS_LABEL_SP)
            val cap = chartTextStyle(Color.Black, CAP_LABEL_SP, bold = true)
            val gap = with(density) { LABEL_GAP.toPx() }
            val canvas = with(density) { Size(widthDp.dp.toPx(), 200.dp.toPx()) }
            out = cases.flatMap { case ->
                val cols = columns(case)
                val axis = ChartAxes.of(cols, case.metric)
                val yTicks = yAxisTicks(axis, case.metric.unit, case.system)
                val xTicks = timeTicks(case.range, 0, case.spanS, TimeZone.UTC)
                val ySizes = yTicks.map { measurer.measure(it.second, label).size }
                val xSizes = xTicks.map { measurer.measure(it.second, label).size }
                val guide = measurer.measure(guideText, label).size
                val margins = chartMargins(ySizes.map { it.width }, xSizes.maxOf { it.height }, measurer.measure("0", cap).size.height, gap)
                val geometry = ChartGeometry(canvas, margins, axis, 0, case.spanS)
                val guideline = ChartAxes.guidelineOf(case.metric)?.takeIf { it <= axis.max }
                val placed = placeAxisLabels(geometry, yTicks.map { it.first }, ySizes, xTicks.map { it.first }, xSizes, guideline, guide, gap)
                fun rect(at: Offset, size: IntSize) = Rect(at, Size(size.width.toFloat(), size.height.toFloat()))
                val yRects = placed.y.map { (i, at) -> rect(at, ySizes[i]) }
                val xRects = placed.x.map { (i, at) -> rect(at, xSizes[i]) }
                val all = yRects + xRects + listOfNotNull(placed.guide?.let { rect(it, guide) })
                buildList {
                    all.filterNot { geometry.canvas.contains(it.topLeft) && it.right <= geometry.canvas.right + 0.5f && it.bottom <= geometry.canvas.bottom + 0.5f }
                        .forEach { add("${case.name}: $it leaves the canvas ${geometry.canvas}") }
                    yRects.forEach { if (it.right > geometry.plot.left) add("${case.name}: y label $it runs into the plot at ${geometry.plot.left}") }
                    xRects.forEach { if (it.top < geometry.plot.bottom) add("${case.name}: x label $it runs into the plot at ${geometry.plot.bottom}") }
                    for (rects in listOf(yRects, xRects)) {
                        rects.zipWithNext().forEach { (a, b) -> if (a.overlaps(b)) add("${case.name}: $a overlaps $b") }
                    }
                    if (yRects.size < 2) add("${case.name}: only ${yRects.size} y labels")
                    if (xRects.size < 2) add("${case.name}: only ${xRects.size} x labels")
                }
            }
        }
        return out
    }

    @Test
    fun noAxisLabelIsClippedOrOverlappedAtTwiceTheFontSize() = runComposeUiTest {
        var found: List<String>? = null
        setContent { found = problems(fontScale = 2f, widthDp = 320) }
        waitForIdle()
        assertEquals(emptyList(), found)
    }

    @Test
    fun theLabelsReallyGrowWithTheFontSize() = runComposeUiTest {
        // Guards the test above: the labels it checks are measured at the scaled size, not a fixed px size.
        var small = 0
        var large = 0
        setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, 1f)) { small = rememberTextMeasurer().measure("100", chartTextStyle(Color.Black, AXIS_LABEL_SP)).size.height }
            CompositionLocalProvider(LocalDensity provides Density(base.density, 2f)) { large = rememberTextMeasurer().measure("100", chartTextStyle(Color.Black, AXIS_LABEL_SP)).size.height }
        }
        waitForIdle()
        assertTrue(large > small * 1.6f, "fontScale 2 label is $large px, fontScale 1 is $small px")
    }

    @Test
    fun everyBarStillHasItsNodeAtTwiceTheFontSize() = runComposeUiTest {
        val day = (0 until 24).map { i -> HistoryColumn(i * HOUR, (i + 1) * HOUR, mean = 5f + i, max = 5f + i, coverage = 1f, estimatedShare = 0f) }
        setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, 2f)) {
                MaterialTheme {
                    BandColumnChart(day, Metrics.PM25, HistoryRange.DAY, 0, DAY, selected = null, onSelect = {}, zone = TimeZone.UTC, level = DetailLevel.SIMPLE)
                }
            }
        }
        val bars = onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).fetchSemanticsNodes()
        assertEquals(24, bars.size)
        assertTrue(bars.all { it.size.width > 0 && it.size.height > 0 }, "a bar node collapsed")
    }

    private companion object {
        const val HOUR = 3_600L
        const val DAY = 24 * HOUR
    }
}
