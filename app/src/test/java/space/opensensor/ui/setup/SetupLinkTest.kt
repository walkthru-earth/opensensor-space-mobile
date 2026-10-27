package space.opensensor.ui.setup

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
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
        val running = flowOf(1)
        val seen = flowOf(stale, stale, ConnectionState.Connecting, again).skipStale(stale, running).toList()
        assertEquals(listOf(ConnectionState.Connecting, again), seen)
        // A failure with the same words that is not the old one shows at once.
        assertSame(again, flowOf(again).skipStale(stale, running).toList().single())
        assertEquals(listOf(stale), flowOf(stale).skipStale(null, running).toList())
    }

    @Test
    fun `a retry that fails the same way without a new state shows once its connect ends`() = runTest {
        val stale = ConnectionState.Failed("connection failed")
        val state = MutableStateFlow<ConnectionState>(stale)
        val running = MutableStateFlow(1)
        val seen = mutableListOf<ConnectionState>()
        val job = launch { state.skipStale(stale, running).collect { seen += it } }
        runCurrent()
        assertEquals("the old failure stays hidden while the retry runs", emptyList<ConnectionState>(), seen)
        // The retry failed with equal words, so the StateFlow keeps the old instance and never emits.
        state.value = ConnectionState.Failed("connection failed")
        running.value = 0
        runCurrent()
        assertEquals(listOf<ConnectionState>(stale), seen)
        job.cancel()
    }
}
