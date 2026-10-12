package earth.walkthru.aqsync.data.sync

import earth.walkthru.aqsync.data.protocol.DeviceStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class SmartSyncPolicyTest {
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
    fun `missing clock gets phone time`() {
        val decision = SmartSyncPolicy.decide(DeviceStatus(utc = 0, clockSource = 0))
        assertEquals(ClockQuality.MISSING, decision.quality)
        assertEquals(ClockSyncAction.SET_TIME, decision.action)
    }

    @Test
    fun `rtc clock gets phone time`() {
        val decision = SmartSyncPolicy.decide(DeviceStatus(utc = 1, clockSource = 2))
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
        val decision = SmartSyncPolicy.decide(null)
        assertEquals(ClockQuality.UNKNOWN, decision.quality)
        assertEquals(ClockSyncAction.SET_TIME, decision.action)
    }

    @Test
    fun sixHourAgeRefreshesBothHostAndNetworkAnchors() {
        for (source in listOf(1, 3)) {
            val recent = DeviceStatus(utc = 1, clockSource = source, clockAgeSeconds = 21_599)
            assertEquals(ClockSyncAction.CONTINUE, SmartSyncPolicy.decide(recent).action)
            assertEquals(ClockSyncAction.SET_TIME, SmartSyncPolicy.decide(recent.copy(clockAgeSeconds = 21_600)).action)
        }
    }

    @Test
    fun driftUsesTwoSecondsPlusMeasuredLinkUncertainty() {
        val status = DeviceStatus(utc = 1, clockSource = 1, utcEpochMillis = 100_000, clockAgeSeconds = 30)
        for (direction in listOf(-1, 1)) {
            assertEquals(ClockSyncAction.CONTINUE, SmartSyncPolicy.decide(status, 100_000 + direction * 2_500L, 500).action)
            assertEquals(ClockSyncAction.SET_TIME, SmartSyncPolicy.decide(status, 100_000 + direction * 2_501L, 500).action)
        }
    }

    @Test
    fun missingUtcTakesPrecedenceOverStaleSourceQuality() {
        assertEquals(ClockSyncAction.SET_TIME, SmartSyncPolicy.decide(DeviceStatus(utc = 0, clockSource = 1)).action)
    }
}
