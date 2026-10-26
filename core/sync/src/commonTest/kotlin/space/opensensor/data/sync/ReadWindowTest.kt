package space.opensensor.data.sync

import space.opensensor.data.ble.TransportKind
import space.opensensor.link.LinkSession
import kotlin.test.Test
import kotlin.test.assertEquals

class ReadWindowTest {
    @Test
    fun deviceMaxReadIsUsedWhenItFits() {
        assertEquals(16_384, ReadWindow.forKind(TransportKind.BLE, 16_384))
        assertEquals(16_384, ReadWindow.forKind(TransportKind.LAN, 16_384))
    }

    @Test
    fun missingOrInvalidMaxReadFallsBackToTheDefault() {
        assertEquals(ReadWindow.DEFAULT_MAX_READ, ReadWindow.forKind(TransportKind.BLE, null))
        assertEquals(ReadWindow.DEFAULT_MAX_READ, ReadWindow.forKind(TransportKind.LAN, 0))
        assertEquals(ReadWindow.DEFAULT_MAX_READ, ReadWindow.forKind(TransportKind.LAN, -5))
    }

    @Test
    fun maxReadAboveHalfTheInboxBudgetIsClamped() {
        // A larger window ends every READ in ResponseOverflow, so sync would never finish.
        assertEquals((LinkSession.BLE_BUDGET / 2).toInt(), ReadWindow.forKind(TransportKind.BLE, Int.MAX_VALUE))
        assertEquals((LinkSession.LAN_BUDGET / 2).toInt(), ReadWindow.forKind(TransportKind.LAN, 4 * 1024 * 1024))
        assertEquals(1_000, ReadWindow.forKind(TransportKind.LAN, 16_384, budget = 2_000))
    }

    @Test
    fun theAdaptiveWindowStartsAtTheDeviceCapAndFramesComeFromTheMtu() {
        // MTU 517 on BLE: payload_max is capped at 512, a CHUNK carries 505 bytes.
        val ble = AdaptiveReadWindow.of(TransportKind.BLE, payloadMax = 514, maxRead = 16_384)
        assertEquals(505, ble.frame)
        assertEquals(16_384, ble.max)
        assertEquals(16_384, ble.current)
        assertEquals(4 * 505, ble.min)
        // LAN has no GATT cap, payload_max 1024 carries 1017 bytes.
        val lan = AdaptiveReadWindow.of(TransportKind.LAN, payloadMax = 1_024, maxRead = 16_384)
        assertEquals(1_017, lan.frame)
        // A small MTU gives a small floor, the cap still comes from max_read.
        val small = AdaptiveReadWindow.of(TransportKind.BLE, payloadMax = 244, maxRead = 8_000)
        assertEquals(237, small.frame)
        assertEquals(8_000, small.max)
        assertEquals(4 * 237, small.min)
    }

    @Test
    fun theCapFollowsMaxReadAndItsFallbackNeverAConstant() {
        assertEquals(12_000, AdaptiveReadWindow.of(TransportKind.BLE, 514, 12_000).max)
        assertEquals(ReadWindow.DEFAULT_MAX_READ, AdaptiveReadWindow.of(TransportKind.BLE, 514, null).max)
        assertEquals((LinkSession.BLE_BUDGET / 2).toInt(), AdaptiveReadWindow.of(TransportKind.BLE, 514, Int.MAX_VALUE).max)
        // A device that allows less than the floor is never asked for more than it allows.
        val tiny = AdaptiveReadWindow.of(TransportKind.BLE, 514, 1_000)
        assertEquals(1_000, tiny.min)
        tiny.onLost()
        assertEquals(1_000, tiny.current)
    }

    @Test
    fun aLostWindowHalvesOnWholeFramesDownToTheFloor() {
        val w = AdaptiveReadWindow.of(TransportKind.BLE, 514, 16_384)
        w.onLost()
        assertEquals(16 * 505, w.current)
        w.onLost()
        assertEquals(8 * 505, w.current)
        w.onLost()
        assertEquals(4 * 505, w.current)
        w.onLost()
        assertEquals(w.min, w.current, "the floor holds")
    }

    @Test
    fun cleanWindowsGrowItBackOneStepAtATimeUpToTheCap() {
        val w = AdaptiveReadWindow.of(TransportKind.BLE, 514, 16_384)
        repeat(3) { w.onLost() }
        val low = w.current
        repeat(AdaptiveReadWindow.GROW_AFTER - 1) { w.onClean() }
        assertEquals(low, w.current, "one clean window short of a step")
        w.onClean()
        assertEquals(low + w.min, w.current, "additive increase of one floor step")
        // A loss resets the clean count.
        repeat(AdaptiveReadWindow.GROW_AFTER - 1) { w.onClean() }
        w.onLost()
        val after = w.current
        w.onClean()
        assertEquals(after, w.current)
        repeat(1_000) { w.onClean() }
        assertEquals(w.max, w.current, "never above the device cap")
    }
}
