package space.opensensor.ui.setup

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import space.opensensor.data.ble.BleConnectionFailure
import space.opensensor.data.ble.ConnectionState
import space.opensensor.setup.SetupLink

class SetupLinkTest {
    @Test
    fun `old firmware shows as update firmware even after bonding`() {
        val refused = ConnectionState.Failed(BleConnectionFailure.FIRMWARE_UPDATE_MESSAGE)
        assertEquals(SetupLink.UpdateFirmware, setupLink(refused, proto = null, hasInfo = false, bonding = false))
        assertEquals(SetupLink.UpdateFirmware, setupLink(refused, proto = null, hasInfo = false, bonding = true))
    }

    @Test
    fun `other failures stay pairing or unreachable`() {
        val lost = ConnectionState.Failed("connection failed")
        assertEquals(SetupLink.Unreachable, setupLink(lost, proto = null, hasInfo = false, bonding = false))
        assertEquals(SetupLink.PairingRefused, setupLink(lost, proto = null, hasInfo = false, bonding = true))
        assertEquals(SetupLink.Ready(3), setupLink(ConnectionState.Ready(247), proto = 3, hasInfo = true, bonding = false))
        assertEquals(SetupLink.Connecting, setupLink(ConnectionState.Ready(247), proto = null, hasInfo = false, bonding = false))
    }

    @Test
    fun `a retry skips only the failure it replaced`() = runTest {
        val stale = ConnectionState.Failed("connection failed")
        val again = ConnectionState.Failed("connection failed")
        val seen = flowOf(stale, stale, ConnectionState.Connecting, again).skipStale(stale).toList()
        assertEquals(listOf(ConnectionState.Connecting, again), seen)
        // A failure with the same words that is not the old one shows at once.
        assertSame(again, flowOf(again).skipStale(stale).toList().single())
        assertEquals(listOf(stale), flowOf(stale).skipStale(null).toList())
    }
}
