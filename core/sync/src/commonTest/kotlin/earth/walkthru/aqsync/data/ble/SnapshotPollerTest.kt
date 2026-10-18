package earth.walkthru.aqsync.data.ble

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SnapshotPollerTest {
    @Test
    fun `only a Bluetooth MTU below a full snapshot needs paced reads`() {
        assertTrue(SnapshotPoller.needed(TransportKind.BLE, 247))
        assertTrue(SnapshotPoller.needed(TransportKind.BLE, 482))
        assertFalse(SnapshotPoller.needed(TransportKind.BLE, 483))
        assertFalse(SnapshotPoller.needed(TransportKind.BLE, 517))
        assertFalse(SnapshotPoller.needed(TransportKind.LAN, 247))
    }
}
