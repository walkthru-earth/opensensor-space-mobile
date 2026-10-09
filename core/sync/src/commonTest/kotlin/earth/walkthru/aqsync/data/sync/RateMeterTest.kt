package earth.walkthru.aqsync.data.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class RateMeterTest {
    @Test
    fun firstSampleHasNoRate() {
        val meter = RateMeter(TestTimeSource())
        assertEquals(0.0, meter.update(1_000))
    }

    @Test
    fun rateIsBytesOverElapsedSeconds() {
        val time = TestTimeSource()
        val meter = RateMeter(time)
        meter.update(0)
        time += 500.milliseconds
        assertEquals(2_000.0, meter.update(1_000))
    }

    @Test
    fun oldSamplesAgeOutOfTheThreeSecondWindow() {
        val time = TestTimeSource()
        val meter = RateMeter(time)
        meter.update(0)
        time += 1.seconds
        meter.update(100_000)
        time += 3.seconds
        // The burst in the first second ages out, so only the last 3 s count.
        assertEquals(1_000.0, meter.update(103_000))
    }

    @Test
    fun progressMovingBackStartsANewWindowInsteadOfANegativeRate() {
        val time = TestTimeSource()
        val meter = RateMeter(time)
        meter.update(0)
        time += 1.seconds
        meter.update(50_000)
        time += 1.seconds
        assertEquals(0.0, meter.update(0))
        time += 1.seconds
        assertEquals(4_000.0, meter.update(4_000))
    }
}
