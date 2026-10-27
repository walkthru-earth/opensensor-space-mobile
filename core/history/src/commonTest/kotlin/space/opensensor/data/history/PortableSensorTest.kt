package space.opensensor.data.history

import space.opensensor.family.air.AirFamily
import space.opensensor.family.air.AirMetrics
import space.opensensor.family.air.AirMood
import space.opensensor.family.air.NowReading
import space.opensensor.family.air.moodOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class PortableSensorTest {
    init {
        AirFamily.install()
    }

    @Test fun invalidSensorDoesNotBecomeZero() {
        assertEquals(NowReading.Waiting, NowReading.of(LiveFixtures.waveshare("""{"pms":4}""")))
        assertIs<NowReading.SensorNotReady>(NowReading.of(LiveFixtures.waveshare("""{"pms":1,"pm25":0.0}""")))
        assertNull(AirMetrics.BOARD_TEMP.moodOf(-12.0))
    }

    @Test fun sharedBandsHaveIdenticalBoundaries() {
        assertEquals(AirMood.FRESH, (NowReading.of(LiveFixtures.waveshare("""{"pms":4,"pm25":14.9}""")) as NowReading.Reading).mood)
        assertEquals(AirMood.FRESH, AirMetrics.PM25.moodOf(15.0))
        assertEquals(AirMood.FAIR, (NowReading.of(LiveFixtures.waveshare("""{"pms":4,"pm25":15.5}""")) as NowReading.Reading).mood)
        assertEquals(AirMood.HAZY, AirMetrics.PM25.moodOf(35.0))
        assertEquals(NowReading.Waiting, NowReading.of(LiveFixtures.waveshare(mapOf("pms" to 4.0, "pm25" to Double.NaN))))
    }

    @Test fun clockEstimateRetainsSignedOffsets() {
        val anchor = TimeEstimate.Anchor("boot", 2_000_000L, 1000L, "anchor.parquet")
        assertEquals(1_000_000L, anchor.estimateNs(0L))
        assertEquals(3_000_000L, anchor.estimateNs(2000L))
    }
}
