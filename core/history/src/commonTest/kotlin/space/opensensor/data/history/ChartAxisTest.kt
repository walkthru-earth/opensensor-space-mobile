package space.opensensor.data.history

import space.opensensor.airquality.WhoGuidelines
import space.opensensor.model.PlausibleRange
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChartAxisTest {
    private fun column(mean: Float?, max: Float? = mean) = HistoryColumn(0, 3600, mean, max, 1f, 0f)

    /** A neutral metric with a known plausible range, so these cases do not follow the bundled templates. */
    private val temp = AirMetrics.AMBIENT_TEMP.copy(plausibleRange = PlausibleRange(-40.0, 125.0))

    @Test
    fun `subzero temperature columns and mixed signs use a zero baseline`() {
        val axis = ChartAxes.of(listOf(column(-23f, -19f), column(12f, 14f)), temp)
        assertTrue(axis.min < -23f)
        assertTrue(axis.max >= 14f)
        assertTrue(axis.fraction(-23f) < axis.fraction(0f))
        assertTrue(axis.fraction(12f) > axis.fraction(0f))
        assertTrue(axis.fraction(0f) > 0f && axis.fraction(0f) < 1f)
    }

    @Test
    fun `a negative extreme inside the plausible range is included rather than percentile clipped`() {
        val columns = List(99) { column(10f) } + column(-38f)
        val axis = ChartAxes.of(columns, temp)
        assertTrue(axis.min < -38f)
        assertTrue(axis.fraction(-38f) > 0f)
    }

    @Test
    fun `an implausible reading cannot stretch the axis past the template range`() {
        val axis = ChartAxes.of(List(99) { column(10f) } + column(-800f, 900f), temp)
        assertTrue(axis.min <= -40f && axis.min > -100f, "min ${axis.min}")
        assertTrue(axis.max >= 125f && axis.max < 200f, "max ${axis.max}")
    }

    @Test
    fun `with no data the axis is the plausible range and without one a small positive span`() {
        for (columns in listOf(emptyList(), listOf(column(null)), listOf(column(Float.NaN, Float.POSITIVE_INFINITY)))) {
            val ranged = ChartAxes.of(columns, temp)
            assertTrue(ranged.min <= -40f && ranged.max >= 125f, "${ranged.min}..${ranged.max}")
            val bare = ChartAxes.of(columns, temp.copy(plausibleRange = null))
            assertEquals(0f, bare.min)
            assertTrue(bare.max.isFinite() && bare.max > bare.min)
            assertTrue(bare.step > 0f && bare.span > 0f)
        }
    }

    @Test
    fun `PM keeps its positive cap and snaps up to a WHO edge`() {
        val columns = List(99) { column(12f) } + column(379f)
        val axis = ChartAxes.of(columns, AirMetrics.PM25)
        assertEquals(0f, axis.min)
        // p95 is 12, so the floor rules and snaps to IT-3, with the guideline line in view.
        assertEquals(37.5f, axis.max)
        assertEquals(7.5f, axis.step)
        assertEquals(15f, ChartAxes.guidelineOf(AirMetrics.PM25))
        assertTrue(15f < axis.max)
        // A hazy day snaps to the next edge, not to a round number.
        assertEquals(50f, ChartAxes.of(List(20) { column(40f) }, AirMetrics.PM25).max)
        assertEquals(75f, ChartAxes.of(List(20) { column(55f) }, AirMetrics.PM25).max)
        // Above the loosest interim target the top is round again.
        val smoky = ChartAxes.of(List(20) { column(90f) }, AirMetrics.PM25)
        assertTrue(smoky.max >= 108f && smoky.max % smoky.step == 0f)
    }

    @Test
    fun `PM10 gets its own WHO guideline and neutral metrics none`() {
        val axis = ChartAxes.of(List(10) { column(20f) }, AirMetrics.PM10)
        assertEquals(0f, axis.min)
        assertEquals(75f, axis.max)
        assertEquals(45f, ChartAxes.guidelineOf(AirMetrics.PM10))
        assertTrue(45f < axis.max)
        assertEquals(100f, ChartAxes.of(List(10) { column(70f) }, AirMetrics.PM10).max)
        assertNull(ChartAxes.guidelineOf(AirMetrics.PM1))
        assertNull(ChartAxes.guidelineOf(AirMetrics.AMBIENT_TEMP))
        assertNull(ChartAxes.guidelineOf(AirMetrics.PM25_BATCH_ESTIMATE))
    }

    @Test
    fun `every WHO edge axis has whole or half unit gridlines that land on the top`() {
        for (edge in WhoGuidelines.all.flatMap { it.edges }) {
            val top = edge.toFloat()
            val step = ChartAxes.gridStep(top)
            val lines = top / step
            assertTrue(abs(lines - lines.roundToInt()) < 1e-4f && lines in 3f..6f, "$edge by $step")
        }
    }
}
