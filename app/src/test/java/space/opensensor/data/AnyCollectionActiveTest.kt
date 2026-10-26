package space.opensensor.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import space.opensensor.data.ble.ConnectionState

class AnyCollectionActiveTest {
    @Test
    fun `collect all waits for an interactive sync on any device`() {
        fun device(ownership: DeviceOwnership, running: Boolean = false) =
            DeviceSnapshot("a", null, ConnectionState.Disconnected, SyncProgress(running = running), null, false, null, ownership)
        assertFalse(anyCollectionActive(false, listOf(device(DeviceOwnership.IDLE))))
        assertTrue(anyCollectionActive(false, listOf(device(DeviceOwnership.IDLE), device(DeviceOwnership.INTERACTIVE))))
        assertTrue(anyCollectionActive(false, listOf(device(DeviceOwnership.IDLE, running = true))))
        assertTrue(anyCollectionActive(true, emptyList()))
    }
}
