package earth.walkthru.aqsync.units

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AxisTicksTest {
    private fun close(expected: List<Double>, actual: List<Double>) {
        assertEquals(expected.size, actual.size, "expected $expected but was $actual")
        expected.zip(actual).forEach { (e, a) -> assertTrue(abs(e - a) < 1e-9, "expected $expected but was $actual") }
    }

    @Test
    fun stepsUseOneTwoTwoAndAHalfOrFive() {
        assertEquals(10.0, niceTickStep(0.0, 40.0, 5))
        assertEquals(20.0, niceTickStep(32.0, 104.0, 5))
        assertEquals(2.5, niceTickStep(0.0, 10.0, 5))
        assertEquals(5.0, niceTickStep(0.0, 18.0, 5))
        assertEquals(0.25, niceTickStep(29.2, 30.4, 6))
        assertEquals(100.0, niceTickStep(0.0, 900.0, 10))
        assertEquals(0.0, niceTickStep(5.0, 5.0, 5))
    }

    @Test
    fun ticksAreRoundAndInsideTheRange() {
        close(listOf(0.0, 10.0, 20.0, 30.0, 40.0), niceTicks(0.0, 40.0, 5))
        close(listOf(40.0, 60.0, 80.0, 100.0), niceTicks(32.0, 104.0, 5))
        close(listOf(-20.0, -10.0, 0.0, 10.0, 20.0), niceTicks(-23.0, 23.0, 5))
        close(listOf(29.25, 29.5, 29.75, 30.0, 30.25), niceTicks(29.2, 30.4, 6))
        close(listOf(0.1, 0.2, 0.3), niceTicks(0.1, 0.3, 3))
    }

    @Test
    fun degenerateRanges() {
        assertEquals(listOf(5.0), niceTicks(5.0, 5.0, 5))
        assertEquals(emptyList(), niceTicks(Double.NaN, 1.0, 5))
        assertEquals(emptyList(), niceTicks(0.0, Double.POSITIVE_INFINITY, 5))
        assertEquals(emptyList(), niceTicks(3.0, 1.0, 5))
        // A target below two still yields a usable step.
        close(listOf(0.0, 25.0), niceTicks(0.0, 40.0, 0))
    }

    @Test
    fun decimalsFollowTheStep() {
        assertEquals(0, tickDecimals(20.0))
        assertEquals(1, tickDecimals(2.5))
        assertEquals(1, tickDecimals(0.30000000000000004 - 0.2))
        assertEquals(2, tickDecimals(0.25))
        assertEquals(0, tickDecimals(0.0))
    }

    @Test
    fun fahrenheitTicksAreRoundAndPlacedOnTheCelsiusScale() {
        val ticks = displayAxisTicks(0.0, 40.0, "°C", UnitSystem.IMPERIAL, 5)
        close(listOf(40.0, 60.0, 80.0, 100.0), ticks.map { it.shown })
        close(listOf(40.0, 60.0, 80.0, 100.0).map { (it - 32.0) * 5.0 / 9.0 }, ticks.map { it.source })
        ticks.forEach { assertTrue(it.source in 0.0..40.0) }
    }

    @Test
    fun metricAndUnconvertedUnitsKeepSourceTicks() {
        val metric = displayAxisTicks(0.0, 40.0, "°C", UnitSystem.METRIC, 5)
        close(listOf(0.0, 10.0, 20.0, 30.0, 40.0), metric.map { it.source })
        assertEquals(metric.map { it.source }, metric.map { it.shown })
        val pm = displayAxisTicks(0.0, 60.0, "µg/m³", UnitSystem.IMPERIAL, 4)
        close(listOf(0.0, 20.0, 40.0, 60.0), pm.map { it.shown })
    }
}
