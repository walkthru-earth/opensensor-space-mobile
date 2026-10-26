package space.opensensor.history

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import space.opensensor.data.history.ChartAxis
import space.opensensor.data.history.HistoryColumn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The bar chart's screen reader nodes and its measured margins (#99). */
class BarSemanticsTest {
    private fun hour(index: Int, mean: Float?) = HistoryColumn(
        startS = index * HOUR,
        endS = (index + 1) * HOUR,
        mean = mean,
        max = mean,
        coverage = if (mean == null) 0f else 1f,
        estimatedShare = 0f,
    )

    @Test
    fun onlyBarsWithAReadingAreRead() {
        val bars = readableBars(listOf(hour(2, 9f), hour(0, 12f), hour(1, null), hour(3, Float.NaN)))
        assertEquals(listOf(0L, 2 * HOUR), bars.map { it.startS })
    }

    @Test
    fun marginsFollowTheMeasuredLabels() {
        val small = chartMargins(yLabelWidths = listOf(10, 24, 18), xLabelHeight = 14, capHeight = 12, gap = 4f)
        assertEquals(ChartMargins(left = 28f, top = 16f, bottom = 22f), small)
        // At twice the font size the labels measure twice as large and the margins grow with them.
        val large = chartMargins(yLabelWidths = listOf(20, 48, 36), xLabelHeight = 28, capHeight = 24, gap = 4f)
        assertEquals(ChartMargins(left = 52f, top = 28f, bottom = 36f), large)
    }

    @Test
    fun aChartWithoutYLabelsKeepsOnlyTheGap() {
        assertEquals(4f, chartMargins(emptyList(), xLabelHeight = 0, capHeight = 0, gap = 4f).left)
    }

    @Test
    fun crowdedYLabelsThinOutInsteadOfOverlapping() {
        // 0 to 100 in steps of 10 on a 100 px plot leaves 10 px per tick, the labels are 24 px tall.
        val geometry = ChartGeometry(Size(300f, 140f), ChartMargins(left = 30f, top = 20f, bottom = 20f), ChartAxis(0f, 100f, 10f), 0, 100)
        val ticks = (0..10).map { it * 10f }
        val labels = placeAxisLabels(geometry, ticks, ticks.map { IntSize(20, 24) }, emptyList(), emptyList(), null, IntSize.Zero, gap = 4f)
        assertTrue(labels.y.size in 2 until ticks.size, "kept ${labels.y.size}")
        assertEquals(0, labels.y.first().first, "zero keeps its label")
        labels.y.zipWithNext().forEach { (lower, upper) -> assertTrue(upper.second.y + 24 + 4 <= lower.second.y, "$upper touches $lower") }
        labels.y.forEach { (_, at) -> assertTrue(at.y >= 0f && at.y + 24 <= 140f, "$at leaves the canvas") }
    }

    @Test
    fun xLabelsStayInsideThePlotAndNeverTouch() {
        val geometry = ChartGeometry(Size(200f, 100f), ChartMargins(left = 20f, top = 10f, bottom = 30f), ChartAxis(0f, 10f, 5f), 0, 100)
        val at = listOf(0L, 10L, 50L, 100L)
        val labels = placeAxisLabels(geometry, emptyList(), emptyList(), at, at.map { IntSize(40, 20) }, null, IntSize.Zero, gap = 4f)
        assertEquals(listOf(0, 2, 3), labels.x.map { it.first }, "the label at 10 would touch the one at 0")
        assertEquals(Offset(20f, 78f), labels.x.first().second)
        assertEquals(160f, labels.x.last().second.x, "the last label ends at the plot edge")
    }

    @Test
    fun theGuidelineLabelGoesBelowALineAtTheTop() {
        val geometry = ChartGeometry(Size(200f, 100f), ChartMargins(left = 20f, top = 10f, bottom = 20f), ChartAxis(0f, 15f, 5f), 0, 100)
        val top = assertNotNull(placeAxisLabels(geometry, emptyList(), emptyList(), emptyList(), emptyList(), 15f, IntSize(60, 18), gap = 4f).guide)
        assertEquals(Offset(140f, 12f), top)
        val mid = assertNotNull(placeAxisLabels(geometry, emptyList(), emptyList(), emptyList(), emptyList(), 7.5f, IntSize(60, 18), gap = 4f).guide)
        assertEquals(45f - 18f - 2f, mid.y)
    }

    @Test
    fun labelsCloserThanTheGapAreSkipped() {
        // A 100 px plot, so a value or a time lands on its own number of px.
        val geometry = ChartGeometry(Size(120f, 120f), ChartMargins(left = 20f, top = 10f, bottom = 10f), ChartAxis(0f, 100f, 10f), 0, 100)
        // The label at 12 would end 2 px under the one at 0, less than the 4 px gap.
        val y = placeAxisLabels(geometry, listOf(0f, 12f), listOf(IntSize(10, 10), IntSize(10, 10)), emptyList(), emptyList(), null, IntSize.Zero, gap = 4f)
        assertEquals(listOf(0), y.y.map { it.first })
        // The label at 62 would start 2 px after the one at 0 ends.
        val x = placeAxisLabels(geometry, emptyList(), emptyList(), listOf(0L, 62L), listOf(IntSize(40, 10), IntSize(40, 10)), null, IntSize.Zero, gap = 4f)
        assertEquals(listOf(0), x.x.map { it.first })
    }

    @Test
    fun aTallGuidelineLabelBelowItsLineStaysInsideTheCanvas() {
        // The line is at the top, 10 px down a 30 px canvas, and the label is 25 px tall.
        val geometry = ChartGeometry(Size(200f, 30f), ChartMargins(left = 20f, top = 10f, bottom = 0f), ChartAxis(0f, 15f, 5f), 0, 100)
        val at = assertNotNull(placeAxisLabels(geometry, emptyList(), emptyList(), emptyList(), emptyList(), 15f, IntSize(60, 25), gap = 4f).guide)
        assertEquals(5f, at.y, "pulled up so its bottom is the canvas bottom")
    }

    private companion object {
        const val HOUR = 3_600L
    }
}
