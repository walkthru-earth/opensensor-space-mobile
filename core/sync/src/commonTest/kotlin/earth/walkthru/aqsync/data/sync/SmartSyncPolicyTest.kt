package earth.walkthru.aqsync.data.sync

import earth.walkthru.aqsync.data.protocol.DeviceStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class SmartSyncPolicyTest {
    @Test
    fun networkAnchorDoesNotNeedAnotherPhoneUpdate() {
        val decision = SmartSyncPolicy.decide(DeviceStatus(utc = 1, clockSource = 3), ClockSyncMode.AUTOMATIC)
        assertEquals(ClockQuality.HOST_ANCHORED, decision.quality)
        assertEquals(ClockSyncAction.CONTINUE, decision.action)
    }

    @Test
    fun `legacy host clock without age or drift is retained`() {
        val decision = SmartSyncPolicy.decide(DeviceStatus(utc = 1, clockSource = 1), ClockSyncMode.AUTOMATIC)
        assertEquals(ClockQuality.HOST_ANCHORED, decision.quality)
        assertEquals(ClockSyncAction.CONTINUE, decision.action)
    }

    @Test
    fun `missing clock asks by default`() {
        val decision = SmartSyncPolicy.decide(DeviceStatus(utc = 0, clockSource = 0), ClockSyncMode.ASK)
        assertEquals(ClockQuality.MISSING, decision.quality)
        assertEquals(ClockSyncAction.ASK_USER, decision.action)
    }

    @Test
    fun `automatic mode refreshes rtc clock`() {
        val decision = SmartSyncPolicy.decide(DeviceStatus(utc = 1, clockSource = 2), ClockSyncMode.AUTOMATIC)
        assertEquals(ClockQuality.RTC_RESTORED, decision.quality)
        assertEquals(ClockSyncAction.SET_TIME, decision.action)
    }

    @Test
    fun `never mode preserves missing clock`() {
        val decision = SmartSyncPolicy.decide(DeviceStatus(utc = 0, clockSource = 0), ClockSyncMode.NEVER)
        assertEquals(ClockSyncAction.CONTINUE, decision.action)
    }

    @Test
    fun `legacy utc status is treated as anchored`() {
        val decision = SmartSyncPolicy.decide(DeviceStatus(utc = 1), ClockSyncMode.AUTOMATIC)
        assertEquals(ClockQuality.HOST_ANCHORED, decision.quality)
        assertEquals(ClockSyncAction.CONTINUE, decision.action)
    }

    @Test
    fun `unknown status obtains initial anchor`() {
        val decision = SmartSyncPolicy.decide(null, ClockSyncMode.AUTOMATIC)
        assertEquals(ClockQuality.UNKNOWN, decision.quality)
        assertEquals(ClockSyncAction.SET_TIME, decision.action)
    }

    @Test
    fun sixHourAgeRefreshesBothHostAndNetworkAnchors() {
        for (source in listOf(1, 3)) {
            val recent = DeviceStatus(utc = 1, clockSource = source, clockAgeSeconds = 21_599)
            assertEquals(ClockSyncAction.CONTINUE, SmartSyncPolicy.decide(recent, ClockSyncMode.AUTOMATIC).action)
            assertEquals(ClockSyncAction.SET_TIME, SmartSyncPolicy.decide(recent.copy(clockAgeSeconds = 21_600), ClockSyncMode.AUTOMATIC).action)
        }
    }

    @Test
    fun driftUsesTwoSecondsPlusMeasuredLinkUncertainty() {
        val status = DeviceStatus(utc = 1, clockSource = 1, utcEpochMillis = 100_000, clockAgeSeconds = 30)
        for (direction in listOf(-1, 1)) {
            assertEquals(ClockSyncAction.CONTINUE, SmartSyncPolicy.decide(status, ClockSyncMode.AUTOMATIC, 100_000 + direction * 2_500L, 500).action)
            assertEquals(ClockSyncAction.SET_TIME, SmartSyncPolicy.decide(status, ClockSyncMode.AUTOMATIC, 100_000 + direction * 2_501L, 500).action)
        }
    }

    @Test
    fun missingUtcTakesPrecedenceOverStaleSourceQuality() {
        assertEquals(ClockSyncAction.SET_TIME, SmartSyncPolicy.decide(DeviceStatus(utc = 0, clockSource = 1), ClockSyncMode.AUTOMATIC).action)
    }

    @Test
    fun neverPreservesOldOrDriftingAnchors() {
        val status = DeviceStatus(utc = 1, clockSource = 1, utcEpochMillis = 100_000, clockAgeSeconds = 21_600)
        assertEquals(ClockSyncAction.CONTINUE, SmartSyncPolicy.decide(status, ClockSyncMode.NEVER, 200_000).action)
    }
}
