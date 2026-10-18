package earth.walkthru.aqsync.data.ble

import earth.walkthru.aqsync.data.protocol.ConfigPatch
import earth.walkthru.aqsync.data.protocol.Frame
import earth.walkthru.aqsync.data.protocol.Opcode
import earth.walkthru.aqsync.data.protocol.PairMode
import earth.walkthru.aqsync.link.LinkSession
import earth.walkthru.aqsync.link.ScriptedLinkDriver
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A `SET_CONFIG` carrying `wifi.psk` or `ble.pin` never reaches a LAN driver (#155). Every patch
 * value here is an obvious placeholder, never a real credential.
 */
class SecretPatchLinkTest {
    private val pskPatch = ConfigPatch.build { wifiSsid("<dev ssid>"); wifiPsk("<dev psk>"); wifiOn(true) }
    private val pinPatch = ConfigPatch.build { blePair(PairMode.FIXED); blePin("000000") }
    private val plainPatch = ConfigPatch.build { wifiOn(false); lanOn(true) }

    /**
     * A driver of [driverKind] behind a transport that claims [label]. iOS can label a connection
     * Bluetooth while its selector hands out the LAN link, so the two may disagree.
     */
    private suspend fun TestScope.session(driverKind: TransportKind, label: TransportKind = driverKind): Pair<ScriptedLinkDriver, DeviceSession> {
        val driver = ScriptedLinkDriver(kind = driverKind) { op, _ ->
            if (op == Opcode.SET_CONFIG) emit(Frame.Config(0, "{}"))
        }
        val link = LinkSession(driver, backgroundScope, timeSource = testScheduler.timeSource)
        link.open()
        return driver to DeviceSession(link, LabelTransport(label))
    }

    @Test
    fun aPassphrasePatchNeverReachesALanDriver() = runTest {
        val (driver, session) = session(TransportKind.LAN)
        val refused = assertFailsWith<SecretNeedsBluetoothException> { session.setConfig(pskPatch) }
        assertEquals(TransportKind.LAN, refused.kind)
        assertTrue(driver.sent.isEmpty())
    }

    @Test
    fun aPinPatchNeverReachesALanDriver() = runTest {
        val (driver, session) = session(TransportKind.LAN)
        assertFailsWith<SecretNeedsBluetoothException> { session.setConfig(pinPatch) }
        assertTrue(driver.sent.isEmpty())
    }

    @Test
    fun aLanDriverLabelledBluetoothStillRefusesTheSecret() = runTest {
        val (driver, session) = session(TransportKind.LAN, label = TransportKind.BLE)
        assertFailsWith<SecretNeedsBluetoothException> { session.setConfig(pskPatch) }
        assertTrue(driver.sent.isEmpty())
    }

    @Test
    fun aPlainPatchStillGoesOverLan() = runTest {
        val (driver, session) = session(TransportKind.LAN)
        session.setConfig(plainPatch)
        assertEquals(listOf(Opcode.SET_CONFIG), driver.sent)
    }

    @Test
    fun secretsGoOverBluetooth() = runTest {
        val (driver, session) = session(TransportKind.BLE)
        session.setConfig(pskPatch)
        session.setConfig(pinPatch)
        assertEquals(listOf(Opcode.SET_CONFIG, Opcode.SET_CONFIG), driver.sent)
    }

    @Test
    fun onlyBluetoothAndTheInProcessFakeCarrySecrets() {
        assertTrue(TransportKind.BLE.carriesSecrets)
        assertTrue(TransportKind.FAKE.carriesSecrets)
        assertFalse(TransportKind.LAN.carriesSecrets)
    }

    /** Only identity and the label. Replies run through the link, never this transport. */
    private class LabelTransport(override val kind: TransportKind) : AqTransport {
        override val address = "synthetic-secret-test"
        override val displayName: String? = null
        override val endpoint = address
        override val mtu = 247
        override val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Ready(mtu))
        override val responseFrames = Channel<ByteArray>(Channel.UNLIMITED)
        override val statusNotifications: Flow<ByteArray> = emptyFlow()
        override val liveNotifications: Flow<ByteArray> = emptyFlow()
        override suspend fun connect() = Unit
        override suspend fun disconnect() = Unit
        override suspend fun readInfo(): ByteArray = error("unused")
        override suspend fun readStatus(): ByteArray = error("unused")
        override suspend fun readLive(): ByteArray = error("unused")
        override suspend fun writeControl(request: ByteArray) = error("replies run through the link")
        override fun close() = responseFrames.cancel()
    }
}
