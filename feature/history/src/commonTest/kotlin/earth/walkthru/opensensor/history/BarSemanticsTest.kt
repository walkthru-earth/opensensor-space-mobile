package earth.walkthru.opensensor.history

import earth.walkthru.aqsync.data.history.HistoryColumn
import kotlin.test.Test
import kotlin.test.assertEquals

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

    private companion object {
        const val HOUR = 3_600L
    }
}
