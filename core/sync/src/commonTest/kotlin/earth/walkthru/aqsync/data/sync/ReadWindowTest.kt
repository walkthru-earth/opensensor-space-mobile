package earth.walkthru.aqsync.data.sync

import earth.walkthru.aqsync.data.ble.TransportKind
import earth.walkthru.aqsync.link.LinkSession
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
}
