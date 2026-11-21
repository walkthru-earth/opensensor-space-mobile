package space.opensensor

import space.opensensor.data.ble.ConnectionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SensorLinkTest {
    @Test
    fun aLostPairingShowsTheRepairNotDisconnected() {
        val lost = SensorLink.state(ready = false, pairingLost = true, connecting = false)
        assertTrue(SensorLink.needsRepair(lost))
        assertEquals(lost, SensorLink.state(ready = false, pairingLost = true, connecting = true), "the repair outranks a pending connect")
        assertEquals(ConnectionState.Ready(0), SensorLink.state(ready = true, pairingLost = true, connecting = false))
        assertEquals(ConnectionState.Disconnected, SensorLink.state(ready = false, pairingLost = false, connecting = false))
        assertFalse(SensorLink.needsRepair(ConnectionState.Failed("timeout")))
        assertFalse(SensorLink.needsRepair(ConnectionState.Disconnected))
    }
}
