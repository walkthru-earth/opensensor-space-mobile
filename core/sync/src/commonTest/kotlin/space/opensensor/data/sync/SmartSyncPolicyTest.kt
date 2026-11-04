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
    fun sixHourAgeRefreshesAHostAnchorAndAMeasuredNetworkAnchor() {
        val recent = DeviceStatus(utc = 1, clockSource = 1, clockAgeSeconds = 21_599)
        assertEquals(ClockSyncAction.CONTINUE, decide(recent))
        assertEquals(ClockSyncAction.SET_TIME, decide(recent.copy(clockAgeSeconds = 21_600)))
        for (source in listOf(1, 3)) {
            // With a measured clock that agrees, an old anchor still refreshes.
            assertEquals(ClockSyncAction.SET_TIME, decide(anchored(source, 500, 21_600)))
            assertEquals(ClockSyncAction.CONTINUE, decide(anchored(source, 500, 21_599)))
        }
    }

    @Test
    fun `network anchor whose drift was not measured is never set however old`() {
        // A cached STATUS read carries no usable utc_ms, so the difference is unknown.
        for (age in listOf(null, 120L, 21_600L, 10_000_000L)) {
            val cached = DeviceStatus(utc = 1, clockSource = 3, clockAgeSeconds = age)
            assertEquals(ClockSyncAction.CONTINUE, decide(cached))
            assertEquals(ClockSyncAction.CONTINUE, decide(cached, uncertainty = 5_000))
        }
    }

    @Test
    fun `no STATUS on a proto 3 link leaves the clock alone`() {
        for (proto in listOf(3, 4)) {
            val decision = SmartSyncPolicy.decide(null, now, 0, proto)
            assertEquals(ClockQuality.UNKNOWN, decision.quality)
            assertEquals(ClockSyncAction.CONTINUE, decision.action)
        }
        // Only a legacy caller, with no proto, still asks for an initial anchor.
        assertEquals(ClockSyncAction.SET_TIME, SmartSyncPolicy.decide(null, now, 0, null).action)
        assertEquals(ClockSyncAction.SET_TIME, SmartSyncPolicy.decide(null, now, 0, 2).action)
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
    fun `a host anchor far from the phone is kept until it is six hours old`() {
        for (offset in listOf(61_000L, 90_000L, -3_600_000L)) {
            assertEquals(ClockSyncAction.CONTINUE, decide(anchored(1, offset, 120)))
            assertEquals(ClockSyncAction.CONTINUE, decide(anchored(1, offset, 21_599)))
            assertEquals(ClockSyncAction.SET_TIME, decide(anchored(1, offset, 21_600)))
        }
        // A legacy status with no age cannot show the anchor is recent, so it is replaced.
        assertEquals(ClockSyncAction.SET_TIME, decide(anchored(1, 90_000L, null)))
    }

    @Test
    fun `two phones ninety seconds apart do not flip-flop the clock`() {
        val phoneA = now
        val phoneB = now + 90_000L
        // Phone A just set the sensor. Phone B connects two minutes later and keeps A's anchor.
        val setByA = DeviceStatus(utc = 1, clockSource = 1, utcEpochMillis = phoneA + 120_000L, clockAgeSeconds = 120)
        assertEquals(ClockSyncAction.CONTINUE, decide(setByA, phone = phoneB + 120_000L))
        // Phone A reconnects and still agrees with its own anchor.
        assertEquals(ClockSyncAction.CONTINUE, decide(setByA.copy(clockAgeSeconds = 600), phone = phoneA + 120_000L))
        // Only once the anchor is six hours old does phone B refresh it, and then A keeps B's.
        val late = 21_600_000L
        assertEquals(
            ClockSyncAction.SET_TIME,
            decide(setByA.copy(utcEpochMillis = phoneA + late, clockAgeSeconds = 21_600), phone = phoneB + late),
        )
        val setByB = DeviceStatus(utc = 1, clockSource = 1, utcEpochMillis = phoneB + late + 60_000L, clockAgeSeconds = 60)
        assertEquals(ClockSyncAction.CONTINUE, decide(setByB, phone = phoneA + late + 60_000L))
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
