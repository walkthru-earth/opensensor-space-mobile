@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package earth.walkthru.opensensor

import earth.walkthru.aqsync.data.ble.SecretNeedsBluetoothException
import earth.walkthru.aqsync.data.protocol.ConfigPatch
import earth.walkthru.aqsync.data.protocol.Frame
import earth.walkthru.aqsync.data.protocol.FrameCodec
import earth.walkthru.aqsync.data.protocol.Opcode
import earth.walkthru.aqsync.data.protocol.PairMode
import earth.walkthru.opensensor.device.DeviceConfigChange
import earth.walkthru.opensensor.device.DeviceConfigNotice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import platform.Foundation.NSData
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A passphrase or PIN patch never reaches the Wi-Fi socket on iOS (#155), and Device settings shows
 * the Bluetooth only notice when it is refused (#169). The values are obvious placeholders, never
 * real credentials.
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

    private class NoRecords : KnownRecordStorage {
        override fun load(): String? = null
        override fun save(text: String) = Unit
    }

    /**
     * Device settings for a sensor open over Bluetooth, whose open links are [wifi] and [bluetooth],
     * Wi-Fi first as Swift orders them. Returns the notices the screen would show.
     */
    private fun TestScope.settings(wifi: IosLink?, bluetooth: IosLink?): Pair<IosDeviceConfigSource, List<DeviceConfigNotice>> {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val address = "synthetic-peripheral"
        val state = CompanionState().apply {
            connectionStarted(address)
            check(receiveInfo("""{"proto":3,"dev":"aabbccddeeff"}"""))
        }
        val devices = DevicesBridge(state, emptyFlow(), NoRecords(), { 0L }, {}, {}, { _, _ -> }, { _, _ -> })
        val links = DeviceConfigLinks(
            openConfigLink = { wifi ?: bluetooth },
            openBluetoothLink = { bluetooth },
            saveWifiAccess = {},
            forgetWifiAccess = {},
            connect = {},
            connectBluetooth = {},
            reopen = {},
            disconnect = {},
            scanLan = {},
            scanSticker = null,
        )
        val scope = CoroutineScope(backgroundScope.coroutineContext + dispatcher)
        val source = IosDeviceConfigSource(address, state, devices, links, scope)
        val notices = mutableListOf<DeviceConfigNotice>()
        scope.launch { source.notices.collect { notices += it } }
        runCurrent()
        return source to notices
    }

    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()

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

    @Test
    fun aRefusedSecretShowsTheBluetoothOnlyNoticeWhenOnlyWifiIsOpen() = runTest {
        val port = RecordingPort()
        val wifi = link(port, overWifi = true)
        val (source, notices) = settings(wifi = wifi, bluetooth = null)
        try {
            source.applyConfig(pskPatch, DeviceConfigChange.WIFI_SENT)
            runCurrent()
            source.applyConfig(pinPatch, DeviceConfigChange.PAIRING)
            runCurrent()
            assertEquals(listOf<DeviceConfigNotice>(DeviceConfigNotice.SecretNeedsBluetooth, DeviceConfigNotice.SecretNeedsBluetooth), notices)
            assertTrue(port.sent.isEmpty(), "nothing reached the Wi-Fi socket")
        } finally {
            wifi.close()
            runCurrent()
        }
    }

    @Test
    fun aRefusedSecretShowsTheBluetoothOnlyNoticeWhenNoLinkIsOpen() = runTest {
        val (source, notices) = settings(wifi = null, bluetooth = null)
        source.applyConfig(pskPatch, DeviceConfigChange.WIFI_SENT)
        runCurrent()
        assertEquals(listOf<DeviceConfigNotice>(DeviceConfigNotice.SecretNeedsBluetooth), notices)
    }

    @Test
    fun aSecretTakesTheBluetoothLinkWhenWifiIsAlsoOpen() = runTest {
        val wifiPort = RecordingPort()
        val blePort = RecordingPort()
        val wifi = link(wifiPort, overWifi = true)
        val ble = link(blePort, overWifi = false)
        val (source, notices) = settings(wifi = wifi, bluetooth = ble)
        try {
            source.applyConfig(pskPatch, DeviceConfigChange.WIFI_SENT)
            runCurrent()
            assertEquals(listOf(Opcode.SET_CONFIG), blePort.sent)
            assertTrue(wifiPort.sent.isEmpty(), "nothing reached the Wi-Fi socket")
            ble.reply(Frame.Config(0, "{}"))
            runCurrent()
            assertEquals(listOf<DeviceConfigNotice>(DeviceConfigNotice.Saved(DeviceConfigChange.WIFI_SENT, rebootRequired = false)), notices)
        } finally {
            wifi.close()
            ble.close()
            runCurrent()
        }
    }
}
