package space.opensensor.ui.device

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import space.opensensor.data.lan.LanDevice
import space.opensensor.data.local.database.KnownDevice
import java.net.InetAddress

class ReconnectRouteTest {
    private val thing = "a9de274b-9c37-4b24-a354-1bc198e54381"
    private val known = KnownDevice("AA:BB:CC:DD:EE:FF", "OS-1234", thing, "aabbccddeeff", null, 0, null, lanToken = "encrypted")
    private val target = LanDevice("OS-1234", InetAddress.getByName("192.0.2.2"), 47390, mapOf("thing" to thing), 0)

    @Test
    fun `LAN only reconnect requires enrolled token and resolved matching thing`() {
        assertTrue(hasLanReconnectTarget(known, mapOf(thing to target)))
        assertFalse(hasLanReconnectTarget(known.copy(lanToken = null), mapOf(thing to target)))
        assertFalse(hasLanReconnectTarget(known.copy(thing = "other"), mapOf(thing to target)))
        assertFalse(hasLanReconnectTarget(known, emptyMap()))
        assertFalse(hasLanReconnectTarget(known, mapOf(thing to target.copy(host = null))))
        assertFalse(hasLanReconnectTarget(known, mapOf(thing to target.copy(port = 0))))
    }

    @Test
    fun `Bluetooth and Local Network access decide reconnect preference`() {
        assertTrue(canReconnectLanOnly(bluetoothReady = false, localNetworkReady = true, enrolledLanSeen = true))
        assertFalse(canReconnectLanOnly(bluetoothReady = true, localNetworkReady = true, enrolledLanSeen = true))
        assertFalse(canReconnectLanOnly(bluetoothReady = false, localNetworkReady = false, enrolledLanSeen = true))
        assertFalse(canReconnectLanOnly(bluetoothReady = false, localNetworkReady = true, enrolledLanSeen = false))
    }
}
