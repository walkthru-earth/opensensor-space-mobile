package earth.walkthru.aqsync.data.sync

import earth.walkthru.aqsync.data.protocol.DeviceStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class SmartSyncPolicyTest {
    @Test
    fun `host anchored clock never changes`() {
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
    fun `unknown status does not guess or mutate`() {
        val decision = SmartSyncPolicy.decide(null, ClockSyncMode.AUTOMATIC)
        assertEquals(ClockQuality.UNKNOWN, decision.quality)
        assertEquals(ClockSyncAction.CONTINUE, decision.action)
    }
}
