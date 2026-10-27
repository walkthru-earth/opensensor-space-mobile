package space.opensensor.history

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import space.opensensor.data.history.AirMetrics
import space.opensensor.data.history.HistoryColumn
import space.opensensor.data.history.HistoryRange
import space.opensensor.data.history.startOfDay
import space.opensensor.settings.DetailLevel
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #141. Each bar and calendar day node sits on the mark it names, so the screen reader focus box and
 * explore by touch land on that bar. The bars are narrower than the 48 dp minimum touch size, which
 * is what used to shift every node one bar to the left.
 *
 * A node's bounds are checked twice. Its layout `boundsInRoot`, and the region a screen reader gets,
 * which is its `touchBoundsInRoot` minus the touch bounds of every node after it in the tree, as the
 * Android accessibility delegate computes it. Both centres must match the drawn centre.
 */
@OptIn(ExperimentalTestApi::class)
class ChartNodeBoundsTest {
    private fun column(startS: Long, endS: Long, mean: Float) = HistoryColumn(startS, endS, mean = mean, max = mean, coverage = 1f, estimatedShare = 0f)

    /** The day as 24 hourly bars with a gap at 05:00, about 13 dp wide each on a 360 dp chart. */
    private val day = (0 until 24).filter { it != 5 }.map { column(it * HOUR, (it + 1) * HOUR, 6f + it) }

    /** A week as 168 hourly bars, about 2 dp wide each, with a gap at hour 30. */
    private val week = (0 until 7 * 24).filter { it != 30 }.map { column(it * HOUR, (it + 1) * HOUR, 5f + it % 24) }

    /** March 2026 in UTC, a daily bar for every day but the 10th. */
    private val march = (0 until 31).filter { it != 9 }.map { d ->
        val start = startOfDay(LocalDate(2026, 3, 1).plus(d, DateTimeUnit.DAY), TimeZone.UTC)
        column(start, start + DAY, 4f + d)
    }
    private val marchFrom = startOfDay(LocalDate(2026, 3, 1), TimeZone.UTC)
    private val marchTo = startOfDay(LocalDate(2026, 4, 1), TimeZone.UTC)

    @Test
    fun dayBarNodesCentreOnTheirDrawnBars() = checkBars(day.size) {
        BandColumnChart(day, AirMetrics.PM25, HistoryRange.DAY, 0, DAY, selected = null, onSelect = {}, zone = TimeZone.UTC, level = DetailLevel.SIMPLE)
    }

    @Test
    fun weekBarNodesCentreOnTheirDrawnBars() = checkBars(week.size) {
        BandColumnChart(week, AirMetrics.PM25, HistoryRange.WEEK, 0, 7 * DAY, selected = null, onSelect = {}, zone = TimeZone.UTC, level = DetailLevel.SIMPLE)
    }

    @Test
    fun monthBarNodesCentreOnTheirDrawnBars() = checkBars(march.size) {
        BandColumnChart(march, AirMetrics.PM25, HistoryRange.MONTH, marchFrom, marchTo, selected = null, onSelect = {}, zone = TimeZone.UTC, level = DetailLevel.SIMPLE)
    }

    @Test
    fun calendarDayNodesCentreOnTheirDrawnCells() = check(march.size, vertical = true) {
        CalendarHeatmap(march, AirMetrics.PM25, marchFrom, marchTo, onPickDay = {}, zone = TimeZone.UTC)
    }

    /** A bar node spans the plot height, so only its horizontal centre is the bar's. */
    private fun checkBars(count: Int, chart: @Composable () -> Unit) = check(count, vertical = false, chart)

    private fun check(count: Int, vertical: Boolean, chart: @Composable () -> Unit) = runComposeUiTest {
        var frame: ChartDrawing? = null
        setContent {
            CompositionLocalProvider(LocalChartDrawRecorder provides { frame = it }) {
                MaterialTheme {
                    Box(Modifier.width(360.dp).testTag(CHART)) { chart() }
                }
            }
        }
        waitForIdle()
        val origin = onNodeWithTag(CHART).fetchSemanticsNode().boundsInRoot.topLeft
        val marks = frame!!.marks.map { it.translate(origin) }
        val nodes = ArrayList<SemanticsNode>()
        fun walk(node: SemanticsNode) {
            if (node.config.getOrElseNullable(SemanticsProperties.Role) { null } == Role.Button) nodes += node
            node.children.forEach(::walk)
        }
        walk(onRoot(useUnmergedTree = true).fetchSemanticsNode())
        assertEquals(count, marks.size, "drawn marks")
        assertEquals(count, nodes.size, "button nodes")
        val touch = nodes.map { it.touchBoundsInRoot }
        val problems = nodes.indices.flatMap { i ->
            val name = nodes[i].config[SemanticsProperties.ContentDescription].joinToString()
            val spoken = uncovered(touch[i], touch.drop(i + 1))
            listOf("layout" to nodes[i].boundsInRoot, "screen reader" to spoken).mapNotNull { (what, bounds) ->
                val dx = abs(bounds.center.x - marks[i].center.x)
                val dy = if (vertical) abs(bounds.center.y - marks[i].center.y) else 0f
                if (dx > SLACK_PX || dy > SLACK_PX) "$name $what centre ${bounds.center} is not the drawn centre ${marks[i].center}" else null
            }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    /**
     * What is left of [rect] once each of [later] covers it, the way Android removes the touch bounds
     * of every later node from an earlier one. The nodes here lie in rows and columns, so a cover
     * always trims one edge.
     */
    private fun uncovered(rect: Rect, later: List<Rect>): Rect = later.fold(rect) { r, c ->
        if (r.isEmpty || !r.overlaps(c)) return@fold r
        val spansHeight = c.top <= r.top && c.bottom >= r.bottom
        val spansWidth = c.left <= r.left && c.right >= r.right
        when {
            spansHeight && c.left > r.left -> Rect(r.left, r.top, c.left, r.bottom)
            spansHeight -> Rect(minOf(c.right, r.right), r.top, r.right, r.bottom)
            spansWidth && c.top > r.top -> Rect(r.left, r.top, r.right, c.top)
            spansWidth -> Rect(r.left, minOf(c.bottom, r.bottom), r.right, r.bottom)
            else -> r
        }
    }

    private companion object {
        const val CHART = "chart"
        const val HOUR = 3_600L
        const val DAY = 24 * HOUR

        /** Dp to px rounding of the overlay against the canvas. */
        const val SLACK_PX = 1.5f
    }
}
