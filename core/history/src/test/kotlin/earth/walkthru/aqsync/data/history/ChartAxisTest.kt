package earth.walkthru.aqsync.data.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun `PM keeps its positive cap and only PM25 gets a health guideline`() {
        val columns = List(99) { column(12f) } + column(379f)
        val axis = ChartAxes.of(columns, Metrics.PM25)
        assertEquals(0f, axis.min)
        assertEquals(60f, axis.max)
        assertTrue(ChartAxes.hasPm25Guideline(Metrics.PM25))
        assertFalse(ChartAxes.hasPm25Guideline(Metrics.PM10))
        assertFalse(ChartAxes.hasPm25Guideline(Metrics.AMBIENT_TEMP.copy(bands = Metrics.PM25_BANDS)))
    }
}
