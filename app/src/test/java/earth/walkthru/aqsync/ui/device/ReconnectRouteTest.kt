package earth.walkthru.aqsync.ui.device

import earth.walkthru.aqsync.data.lan.LanDevice
import earth.walkthru.aqsync.data.local.database.KnownDevice
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class ReconnectRouteTest {
    private val station = "a9de274b-9c37-4b24-a354-1bc198e54381"
    private val known = KnownDevice("AA:BB:CC:DD:EE:FF", "OS-1234", station, "aabbccddeeff", null, 0, null, lanToken = "encrypted")
    private val target = LanDevice("OS-1234", InetAddress.getByName("192.0.2.2"), 47390, mapOf("station" to station), 0)

    @Test
    fun `LAN only reconnect requires enrolled token and resolved matching station`() {
        assertTrue(hasLanReconnectTarget(known, mapOf(station to target)))
        assertFalse(hasLanReconnectTarget(known.copy(lanToken = null), mapOf(station to target)))
        assertFalse(hasLanReconnectTarget(known.copy(station = "other"), mapOf(station to target)))
        assertFalse(hasLanReconnectTarget(known, emptyMap()))
        assertFalse(hasLanReconnectTarget(known, mapOf(station to target.copy(host = null))))
        assertFalse(hasLanReconnectTarget(known, mapOf(station to target.copy(port = 0))))
    }

    @Test
    fun `Bluetooth and Local Network access decide reconnect preference`() {
        assertTrue(canReconnectLanOnly(bluetoothReady = false, localNetworkReady = true, enrolledLanSeen = true))
        assertFalse(canReconnectLanOnly(bluetoothReady = true, localNetworkReady = true, enrolledLanSeen = true))
        assertFalse(canReconnectLanOnly(bluetoothReady = false, localNetworkReady = false, enrolledLanSeen = true))
        assertFalse(canReconnectLanOnly(bluetoothReady = false, localNetworkReady = true, enrolledLanSeen = false))
    }
}
