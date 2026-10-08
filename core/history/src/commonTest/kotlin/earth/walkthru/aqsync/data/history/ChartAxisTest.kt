package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.airquality.WhoGuidelines
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChartAxisTest {
    private fun column(mean: Float?, max: Float? = mean) = HistoryColumn(0, 3600, mean, max, 1f, 0f)

    @Test
    fun `subzero temperature columns and mixed signs use a zero baseline`() {
        val axis = ChartAxes.of(listOf(column(-23f, -19f), column(12f, 14f)), Metrics.AMBIENT_TEMP)
        assertTrue(axis.min < -23f)
        assertTrue(axis.max >= 14f)
        assertTrue(axis.fraction(-23f) < axis.fraction(0f))
        assertTrue(axis.fraction(12f) > axis.fraction(0f))
        assertTrue(axis.fraction(0f) > 0f && axis.fraction(0f) < 1f)
    }

    @Test
    fun `isolated negative extreme is included rather than percentile clipped`() {
        val columns = List(99) { column(10f) } + column(-800f)
        val axis = ChartAxes.of(columns, Metrics.AMBIENT_TEMP)
        assertTrue(axis.min < -800f)
        assertTrue(axis.fraction(-800f) > 0f)
    }

    @Test
    fun `empty gap and nonfinite readings have a finite nonzero axis`() {
        for (columns in listOf(emptyList(), listOf(column(null)), listOf(column(Float.NaN, Float.POSITIVE_INFINITY)))) {
            val axis = ChartAxes.of(columns, Metrics.AMBIENT_TEMP)
            assertEquals(0f, axis.min)
            assertTrue(axis.max.isFinite() && axis.max >= Metrics.AMBIENT_TEMP.axisFloor)
            assertTrue(axis.step > 0f && axis.span > 0f)
        }
    }

    @Test
    fun `PM keeps its positive cap and snaps up to a WHO edge`() {
        val columns = List(99) { column(12f) } + column(379f)
        val axis = ChartAxes.of(columns, Metrics.PM25)
        assertEquals(0f, axis.min)
        // p95 is 12, so the floor rules and snaps to IT-3, with the guideline line in view.
        assertEquals(37.5f, axis.max)
        assertEquals(7.5f, axis.step)
        assertEquals(15f, ChartAxes.guidelineOf(Metrics.PM25))
        assertTrue(15f < axis.max)
        // A hazy day snaps to the next edge, not to a round number.
        assertEquals(50f, ChartAxes.of(List(20) { column(40f) }, Metrics.PM25).max)
        assertEquals(75f, ChartAxes.of(List(20) { column(55f) }, Metrics.PM25).max)
        // Above the loosest interim target the top is round again.
        val smoky = ChartAxes.of(List(20) { column(90f) }, Metrics.PM25)
        assertTrue(smoky.max >= 108f && smoky.max % smoky.step == 0f)
    }

    @Test
    fun `PM10 gets its own WHO guideline and neutral metrics none`() {
        val axis = ChartAxes.of(List(10) { column(20f) }, Metrics.PM10)
        assertEquals(0f, axis.min)
        assertEquals(75f, axis.max)
        assertEquals(45f, ChartAxes.guidelineOf(Metrics.PM10))
        assertTrue(45f < axis.max)
        assertEquals(100f, ChartAxes.of(List(10) { column(70f) }, Metrics.PM10).max)
        assertNull(ChartAxes.guidelineOf(Metrics.PM1))
        assertNull(ChartAxes.guidelineOf(Metrics.AMBIENT_TEMP))
        assertNull(ChartAxes.guidelineOf(Metrics.PM25_BATCH_ESTIMATE))
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
