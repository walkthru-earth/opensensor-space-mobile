package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.data.protocol.LiveSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class PortableSensorTest {
    @Test fun invalidSensorDoesNotBecomeZero() {
        assertEquals(NowReading.Waiting, NowReading.of(LiveSample(pms = 4)))
        assertIs<NowReading.SensorNotReady>(NowReading.of(LiveSample(pms = 1, pm25 = 0.0)))
        assertNull(Metrics.BOARD_TEMP.moodOf(-12.0))
        assertEquals(false, Metrics.PM25.accepts(1))
    }

    @Test fun sharedBandsHaveIdenticalBoundaries() {
        assertEquals(AirMood.FRESH, (NowReading.of(LiveSample(pms = 4, pm25 = 14.9)) as NowReading.Reading).mood)
        assertEquals(AirMood.FINE, Metrics.PM25.moodOf(15.0))
        assertEquals(AirMood.HAZY, Metrics.PM25.moodOf(35.0))
    }

    @Test fun clockEstimateRetainsSignedOffsets() {
        val anchor = TimeEstimate.Anchor("boot", 2_000_000L, 1000L, "anchor.parquet")
        assertEquals(1_000_000L, anchor.estimateNs(0L))
        assertEquals(3_000_000L, anchor.estimateNs(2000L))
    }
}
