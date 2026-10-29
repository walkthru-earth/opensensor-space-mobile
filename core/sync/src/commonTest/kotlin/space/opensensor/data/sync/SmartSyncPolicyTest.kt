package space.opensensor.data.sync

import space.opensensor.data.protocol.DeviceStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class SmartSyncPolicyTest {
    // A phone time after the build floor.
    private val now = SmartSyncPolicy.PHONE_CLOCK_FLOOR_MILLIS + 86_400_000L

    private fun decide(status: DeviceStatus?, phone: Long = now, uncertainty: Long = 0) =
        SmartSyncPolicy.decide(status, phone, uncertainty).action

    private fun anchored(source: Int, offsetMillis: Long, ageSeconds: Long?) =
        DeviceStatus(utc = 1, clockSource = source, utcEpochMillis = now + offsetMillis, clockAgeSeconds = ageSeconds)

    @Test
    fun networkAnchorDoesNotNeedAnotherPhoneUpdate() {
        val decision = SmartSyncPolicy.decide(DeviceStatus(utc = 1, clockSource = 3))
        assertEquals(ClockQuality.HOST_ANCHORED, decision.quality)
        assertEquals(ClockSyncAction.CONTINUE, decision.action)
    }

    @Test
    fun `legacy host clock without age or drift is retained`() {
        val decision = SmartSyncPolicy.decide(DeviceStatus(utc = 1, clockSource = 1))
        assertEquals(ClockQuality.HOST_ANCHORED, decision.quality)
        assertEquals(ClockSyncAction.CONTINUE, decision.action)
    }

    @Test
    fun `undated sensor gets phone time`() {
        val decision = SmartSyncPolicy.decide(DeviceStatus(utc = 0, clockSource = 0), now)
        assertEquals(ClockQuality.MISSING, decision.quality)
        assertEquals(ClockSyncAction.SET_TIME, decision.action)
    }

    @Test
    fun `rtc clock after a power cut gets phone time`() {
        val decision = SmartSyncPolicy.decide(DeviceStatus(utc = 1, clockSource = 2), now)
        assertEquals(ClockQuality.RTC_RESTORED, decision.quality)
        assertEquals(ClockSyncAction.SET_TIME, decision.action)
    }

    @Test
    fun `legacy utc status is treated as anchored`() {
        val decision = SmartSyncPolicy.decide(DeviceStatus(utc = 1))
        assertEquals(ClockQuality.HOST_ANCHORED, decision.quality)
        assertEquals(ClockSyncAction.CONTINUE, decision.action)
    }

    @Test
    fun `unknown status obtains initial anchor`() {
        val decision = SmartSyncPolicy.decide(null, now)
        assertEquals(ClockQuality.UNKNOWN, decision.quality)
        assertEquals(ClockSyncAction.SET_TIME, decision.action)
    }

    @Test
    fun `phone earlier than the build floor never sends its time`() {
        val early = SmartSyncPolicy.PHONE_CLOCK_FLOOR_MILLIS - 1
        assertEquals(ClockSyncAction.CONTINUE, decide(DeviceStatus(utc = 0, clockSource = 0), early))
        assertEquals(ClockSyncAction.CONTINUE, decide(DeviceStatus(utc = 1, clockSource = 2), early))
        assertEquals(ClockSyncAction.CONTINUE, decide(null, early))
        assertEquals(ClockSyncAction.SET_TIME, decide(null, SmartSyncPolicy.PHONE_CLOCK_FLOOR_MILLIS))
    }

    @Test
    fun sixHourAgeRefreshesBothHostAndNetworkAnchors() {
        for (source in listOf(1, 3)) {
            val recent = DeviceStatus(utc = 1, clockSource = source, clockAgeSeconds = 21_599)
            assertEquals(ClockSyncAction.CONTINUE, decide(recent))
            assertEquals(ClockSyncAction.SET_TIME, decide(recent.copy(clockAgeSeconds = 21_600)))
            // With a measured clock that agrees, an old anchor still refreshes.
            assertEquals(ClockSyncAction.SET_TIME, decide(anchored(source, 500, 21_600)))
            assertEquals(ClockSyncAction.CONTINUE, decide(anchored(source, 500, 21_599)))
        }
    }

    @Test
    fun `correct clock is kept`() {
        assertEquals(ClockSyncAction.CONTINUE, decide(anchored(1, 300, 600)))
        assertEquals(ClockSyncAction.CONTINUE, decide(anchored(3, -300, 600)))
    }

    @Test
    fun `drifted clock with an old or unknown age uses two seconds plus link uncertainty`() {
        for (age in listOf(null, 30_000L)) {
            for (direction in listOf(-1, 1)) {
                val within = anchored(1, direction * 2_500L, age)
                val beyond = anchored(1, direction * 2_501L, age)
                if (age == null) {
                    assertEquals(ClockSyncAction.CONTINUE, decide(within, uncertainty = 500))
                }
                assertEquals(ClockSyncAction.SET_TIME, decide(beyond, uncertainty = 500))
            }
        }
    }

    @Test
    fun `second phone within a minute does not flip a recent anchor`() {
        for (source in listOf(1, 3)) {
            for (offset in listOf(-30_000L, 5_000L, 59_000L)) {
                assertEquals(ClockSyncAction.CONTINUE, decide(anchored(source, offset, 120)))
            }
        }
    }

    @Test
    fun `recent host anchor far from the phone is replaced`() {
        assertEquals(ClockSyncAction.SET_TIME, decide(anchored(1, 61_000L, 120)))
        assertEquals(ClockSyncAction.SET_TIME, decide(anchored(1, -3_600_000L, 120)))
    }

    @Test
    fun `network anchor is never overwritten by a phone far from it`() {
        // A phone set by hand an hour off, or a few minutes off, keeps the sensor's network time.
        for (offset in listOf(3_600_000L, -180_000L)) {
            assertEquals(ClockSyncAction.CONTINUE, decide(anchored(3, offset, 120)))
            assertEquals(ClockSyncAction.CONTINUE, decide(anchored(3, offset, 30_000)))
        }
    }

    @Test
    fun `large drift threshold grows with anchor age and link delay`() {
        // 100 000 s at 50 ppm is 5 s of expected drift on top of the minute.
        val age = 100_000L
        assertEquals(ClockSyncAction.SET_TIME, decide(anchored(1, 64_000L, age)))
        assertEquals(ClockSyncAction.SET_TIME, decide(anchored(3, 64_000L, age)))
        assertEquals(ClockSyncAction.CONTINUE, decide(anchored(3, 65_001L, age)))
        assertEquals(ClockSyncAction.SET_TIME, decide(anchored(3, 65_001L, age), uncertainty = 1_000))
        assertEquals(ClockSyncAction.CONTINUE, decide(anchored(3, 60_001L, 0)))
    }

    @Test
    fun missingUtcTakesPrecedenceOverStaleSourceQuality() {
        assertEquals(ClockSyncAction.SET_TIME, decide(DeviceStatus(utc = 0, clockSource = 1)))
    }
}
