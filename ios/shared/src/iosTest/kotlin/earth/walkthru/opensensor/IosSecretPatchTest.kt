@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package earth.walkthru.opensensor

import earth.walkthru.aqsync.data.ble.SecretNeedsBluetoothException
import earth.walkthru.aqsync.data.protocol.ConfigPatch
import earth.walkthru.aqsync.data.protocol.Frame
import earth.walkthru.aqsync.data.protocol.FrameCodec
import earth.walkthru.aqsync.data.protocol.Opcode
import earth.walkthru.aqsync.data.protocol.PairMode
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A passphrase or PIN patch never reaches the Wi-Fi socket on iOS (#155). The values are obvious
 * placeholders, never real credentials.
 */
class IosSecretPatchTest {
    private val pskPatch = ConfigPatch.build { wifiSsid("<dev ssid>"); wifiPsk("<dev psk>"); wifiOn(true) }
    private val pinPatch = ConfigPatch.build { blePair(PairMode.FIXED); blePin("000000") }
    private val plainPatch = ConfigPatch.build { lanOn(true) }

    private class RecordingPort : IosLinkPort {
        val sent = mutableListOf<Int>()
        override val mtu = 517
        override fun send(frame: NSData, done: (Boolean) -> Unit) {
            sent += frame.toByteArray()[0].toInt() and 0xFF
            done(true)
        }
        override fun setReadPaused(paused: Boolean) = Unit
    }

    private fun TestScope.link(port: IosLinkPort, overWifi: Boolean): IosLink {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return IosLink(port, overWifi, "synthetic-secret-test", dispatcher, dispatcher)
    }

    private fun IosLink.reply(frame: Frame) = offer(FrameCodec.encode(frame).toNSData())

    @Test
    fun aSecretPatchOnAWifiLinkIsRefusedWithNothingWritten() = runTest {
        val port = RecordingPort()
        val link = link(port, overWifi = true)
        try {
            assertFailsWith<SecretNeedsBluetoothException> { link.leased(LeaseKind.CONFIG) { it.setConfig(pskPatch) } }
            assertFailsWith<SecretNeedsBluetoothException> { link.leased(LeaseKind.CONFIG) { it.setConfig(pinPatch) } }
            assertTrue(port.sent.isEmpty())
        } finally {
            link.close()
            runCurrent()
        }
    }

    @Test
    fun aPlainPatchStillGoesOverWifi() = runTest {
        val port = RecordingPort()
        val link = link(port, overWifi = true)
        try {
            val saving = async { link.leased(LeaseKind.CONFIG) { it.setConfig(plainPatch) } }
            runCurrent()
            assertEquals(listOf(Opcode.SET_CONFIG), port.sent)
            link.reply(Frame.Config(0, "{}"))
            runCurrent()
            saving.await()
        } finally {
            link.close()
            runCurrent()
        }
    }

    @Test
    fun aSecretPatchGoesOverBluetooth() = runTest {
        val port = RecordingPort()
        val link = link(port, overWifi = false)
        try {
            val saving = async { link.leased(LeaseKind.CONFIG) { it.setConfig(pskPatch) } }
            runCurrent()
            assertEquals(listOf(Opcode.SET_CONFIG), port.sent)
            link.reply(Frame.Config(0, "{}"))
            runCurrent()
            saving.await()
        } finally {
            link.close()
            runCurrent()
        }
    }

    @Test
    fun theSecretSelectorPicksOnlyTheBluetoothLink() {
        val wifiFirst: (String) -> String? = { "wifi" }
        val bluetooth: (String) -> String? = { "bluetooth" }
        val noBluetooth: (String) -> String? = { null }
        assertEquals("bluetooth", configLinkFor("aa-11", bluetoothOnly = true, anyLink = wifiFirst, bluetoothLink = bluetooth))
        assertNull(configLinkFor("aa-11", bluetoothOnly = true, anyLink = wifiFirst, bluetoothLink = noBluetooth))
        assertEquals("wifi", configLinkFor("aa-11", bluetoothOnly = false, anyLink = wifiFirst, bluetoothLink = bluetooth))
    }
}
