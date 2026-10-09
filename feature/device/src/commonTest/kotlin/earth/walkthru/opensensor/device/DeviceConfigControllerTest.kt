package earth.walkthru.opensensor.device

import earth.walkthru.aqsync.data.ble.ConfigSnapshot
import earth.walkthru.aqsync.data.ble.ConnectionState
import earth.walkthru.aqsync.data.ble.TransportKind
import earth.walkthru.aqsync.data.history.DeviceCapabilities
import earth.walkthru.aqsync.data.protocol.BleConfig
import earth.walkthru.aqsync.data.protocol.ConfigPatch
import earth.walkthru.aqsync.data.protocol.ConfigProblem
import earth.walkthru.aqsync.data.protocol.DeviceConfig
import earth.walkthru.aqsync.data.protocol.DeviceInfo
import earth.walkthru.aqsync.data.protocol.LanConfig
import earth.walkthru.aqsync.data.protocol.PairMode
import earth.walkthru.aqsync.data.protocol.SensorConfig
import earth.walkthru.aqsync.data.protocol.WifiConfig
import earth.walkthru.aqsync.settings.DetailLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SECRET_PIN = "482913"
private const val SECRET_PSK = "correct horse battery"

private val v2Info = DeviceInfo(proto = 2, schema = DeviceCapabilities.WAVESHARE_V2_SCHEMA)

private fun config(wifiState: String = "off", sensor: SensorConfig? = null) = ConfigSnapshot(
    config = DeviceConfig(
        ble = BleConfig(pair = "random", pin = 123456, pinDefault = 0, bonds = 1, display = 1),
        wifi = WifiConfig(on = 1, ssid = "home", pskSet = 1, state = wifiState),
        lan = LanConfig(on = 1, port = 47390, host = "aq-6b40", clients = 0),
        sensor = sensor,
    ),
    rebootRequired = false,
    rawJson = "{\"ble\":{\"pin\":123456}}",
    atEpochMs = 1L,
)

private class FakeSource(
    connection: ConnectionState = ConnectionState.Ready(247),
    transport: TransportKind = TransportKind.BLE,
    syncRunning: Boolean = false,
) : DeviceConfigSource {
    override val snapshot = MutableStateFlow(
        DeviceConfigSnapshot(
            address = "AA",
            connection = connection,
            transport = transport,
            info = v2Info,
            config = config(),
            syncRunning = syncRunning,
        ),
    )
    override val notices = MutableSharedFlow<DeviceConfigNotice>(extraBufferCapacity = 8)
    val calls = mutableListOf<String>()
    val wire = mutableListOf<String>()
    var sensorAnswer: ConfigSnapshot? = null
    val recorded = mutableListOf<String>()

    override fun refreshConfig() {
        calls += "refresh"
    }

    override fun applyConfig(patch: ConfigPatch, change: DeviceConfigChange) {
        calls += "set $change ${patch.describe()}"
        wire += patch.encode()
    }

    override fun reboot() {
        calls += "reboot"
    }

    override fun scanWifi() {
        calls += "scan"
    }

    override fun clearWifiScan() {
        calls += "clearScan"
    }

    override fun fetchToken() {
        calls += "fetchToken"
    }

    override fun rotateToken() {
        calls += "rotateToken"
    }

    override fun clearBonds() {
        calls += "clearBonds"
    }

    override suspend fun applySensorConfig(patch: ConfigPatch): ConfigSnapshot {
        calls += "sensor ${patch.describe()}"
        return sensorAnswer ?: error("no answer")
    }

    override suspend fun recordSensor(serial: String, vendor: String, model: String, batch: String, source: String) {
        recorded += "$vendor $model $serial $batch $source"
    }

    override fun reconnect() {
        calls += "reconnect"
    }

    override fun reconnectOverBluetooth() {
        calls += "reconnectBle"
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceConfigControllerTest {
    private fun TestScope.controller(source: FakeSource, level: DetailLevel = DetailLevel.DETAILED): DeviceConfigController {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        return DeviceConfigController(scope, source, MutableStateFlow(level))
    }

    @Test
    fun readOnReadyReadsOnceEachTimeTheLinkTurnsReadyWithoutAConfig() = runTest {
        val source = FakeSource(connection = ConnectionState.Disconnected)
        source.snapshot.value = source.snapshot.value.copy(config = null)
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        DeviceConfigController(scope, source, MutableStateFlow(DetailLevel.DETAILED), readOnReady = true)
        assertEquals(emptyList(), source.calls)
        source.snapshot.value = source.snapshot.value.copy(connection = ConnectionState.Ready(0))
        assertEquals(listOf("refresh"), source.calls)
        source.snapshot.value = source.snapshot.value.copy(config = config())
        source.snapshot.value = source.snapshot.value.copy(connection = ConnectionState.Disconnected, config = null)
        source.snapshot.value = source.snapshot.value.copy(connection = ConnectionState.Ready(0))
        assertEquals(listOf("refresh", "refresh"), source.calls)
    }

    @Test
    fun withoutReadOnReadyTheControllerNeverReadsByItself() = runTest {
        val source = FakeSource(connection = ConnectionState.Disconnected)
        source.snapshot.value = source.snapshot.value.copy(config = null)
        controller(source)
        source.snapshot.value = source.snapshot.value.copy(connection = ConnectionState.Ready(0))
        assertEquals(emptyList(), source.calls)
    }

    private fun TestScope.collectNotices(c: DeviceConfigController): MutableList<DeviceConfigNotice> {
        val out = mutableListOf<DeviceConfigNotice>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.notices.collect { out += it } }
        return out
    }

    @Test
    fun rebootIsSentOnlyAfterConfirm() = runTest {
        val source = FakeSource()
        val c = controller(source)
        c.requestReboot()
        assertEquals(DeviceConfigDialog.Reboot, c.dialog.value)
        assertEquals(emptyList(), source.calls)
        c.confirm()
        assertNull(c.dialog.value)
        assertEquals(listOf("reboot"), source.calls)
    }

    @Test
    fun rotateTokenIsSentOnlyAfterConfirm() = runTest {
        val source = FakeSource()
        val c = controller(source)
        c.requestRotateToken()
        assertEquals(DeviceConfigDialog.RotateToken, c.dialog.value)
        assertEquals(emptyList(), source.calls)
        c.confirm()
        assertEquals(listOf("rotateToken"), source.calls)
    }

    @Test
    fun forgetBondsIsSentOnlyAfterConfirm() = runTest {
        val source = FakeSource()
        val c = controller(source)
        c.requestForgetBonds()
        assertEquals(DeviceConfigDialog.ForgetBonds, c.dialog.value)
        assertEquals(emptyList(), source.calls)
        c.confirm()
        assertEquals(listOf("clearBonds"), source.calls)
    }

    @Test
    fun pairingNoneOpensItsConfirmationFirst() = runTest {
        val source = FakeSource()
        val c = controller(source)
        c.savePairing(PairingChoice(PairMode.NONE, null))
        assertEquals(DeviceConfigDialog.PairingNone, c.dialog.value)
        assertEquals(emptyList(), source.calls)
        c.confirm()
        assertEquals(listOf("set PAIRING ble.pair"), source.calls)
        assertEquals(listOf("ble.pair=none\n"), source.wire)
    }

    @Test
    fun otherPairingModesNeedNoConfirmation() = runTest {
        val source = FakeSource()
        val c = controller(source)
        c.savePairing(PairingChoice(PairMode.FIXED, SECRET_PIN))
        assertNull(c.dialog.value)
        assertEquals(listOf("set PAIRING ble.pair,ble.pin"), source.calls)
        assertEquals(listOf("ble.pair=fixed\nble.pin=$SECRET_PIN\n"), source.wire)
    }

    @Test
    fun dismissSendsNothing() = runTest {
        val source = FakeSource()
        val c = controller(source)
        c.requestReboot()
        c.dismiss()
        c.requestRotateToken()
        c.dismiss()
        c.requestForgetBonds()
        c.dismiss()
        c.savePairing(PairingChoice(PairMode.NONE, null))
        c.dismiss()
        assertNull(c.dialog.value)
        assertEquals(emptyList(), source.calls)
    }

    @Test
    fun secondConfirmSendsNothing() = runTest {
        val source = FakeSource()
        val c = controller(source)
        c.requestReboot()
        c.confirm()
        c.confirm()
        assertEquals(listOf("reboot"), source.calls)
    }

    @Test
    fun disruptiveRequestsAreRefusedWhileSyncing() = runTest {
        val source = FakeSource(syncRunning = true)
        val c = controller(source)
        c.requestReboot()
        c.requestRotateToken()
        c.requestForgetBonds()
        assertNull(c.dialog.value)
        assertEquals(emptyList(), source.calls)
    }

    @Test
    fun confirmAfterASyncStartedSendsNothingAndSaysBusy() = runTest {
        val source = FakeSource()
        val c = controller(source)
        val notices = collectNotices(c)
        c.requestReboot()
        source.snapshot.value = source.snapshot.value.copy(syncRunning = true)
        c.confirm()
        assertEquals(emptyList(), source.calls)
        assertEquals(listOf<DeviceConfigNotice>(DeviceConfigNotice.Busy), notices)
    }

    @Test
    fun confirmAfterTheLinkWentBusySendsNoPairingNone() = runTest {
        val source = FakeSource()
        val c = controller(source)
        val notices = collectNotices(c)
        c.savePairing(PairingChoice(PairMode.NONE, null))
        source.snapshot.value = source.snapshot.value.copy(busy = true)
        c.confirm()
        assertEquals(emptyList(), source.calls)
        assertEquals(listOf<DeviceConfigNotice>(DeviceConfigNotice.Busy), notices)
    }

    @Test
    fun nothingIsSentWhenNotConnected() = runTest {
        val source = FakeSource(connection = ConnectionState.Disconnected)
        val c = controller(source)
        c.requestReboot()
        c.savePairing(PairingChoice(PairMode.RANDOM, null))
        c.joinWifi(WifiJoin("home", SECRET_PSK, open = false))
        c.setLanOn(false)
        c.fetchToken()
        c.refresh()
        assertNull(c.dialog.value)
        assertEquals(emptyList(), source.calls)
    }

    @Test
    fun joinWifiSendsOneValidatedPatch() = runTest {
        val source = FakeSource()
        val c = controller(source)
        c.joinWifi(WifiJoin("home", SECRET_PSK, open = false))
        assertEquals(listOf("set WIFI_SENT wifi.ssid,wifi.psk,wifi.on"), source.calls)
        assertEquals(listOf("wifi.ssid=home\nwifi.psk=$SECRET_PSK\nwifi.on=1\n"), source.wire)
    }

    @Test
    fun openNetworkSendsAnEmptyPassphrase() = runTest {
        val source = FakeSource()
        val c = controller(source)
        c.joinWifi(WifiJoin("cafe", "typed by mistake", open = true))
        assertEquals(listOf("wifi.ssid=cafe\nwifi.psk=\nwifi.on=1\n"), source.wire)
    }

    @Test
    fun invalidInputBecomesAProblemNotAWrite() = runTest {
        val source = FakeSource()
        val c = controller(source)
        val notices = collectNotices(c)
        c.joinWifi(WifiJoin("", SECRET_PSK, open = false))
        c.savePairing(PairingChoice(PairMode.FIXED, "12a456"))
        assertEquals(emptyList(), source.calls)
        assertEquals(
            listOf<DeviceConfigNotice>(
                DeviceConfigNotice.Problem(ConfigProblem.SSID_EMPTY),
                DeviceConfigNotice.Problem(ConfigProblem.PIN_FORMAT),
            ),
            notices,
        )
    }

    @Test
    fun tokenIsFetchedOverBluetoothOnly() = runTest {
        val source = FakeSource(transport = TransportKind.LAN)
        val c = controller(source)
        c.fetchToken()
        assertEquals(emptyList(), source.calls)
        source.snapshot.value = source.snapshot.value.copy(transport = TransportKind.BLE)
        c.fetchToken()
        assertEquals(listOf("fetchToken"), source.calls)
    }

    @Test
    fun wifiJoinIsPolledWhileConnecting() = runTest {
        val source = FakeSource()
        controller(source)
        source.snapshot.value = source.snapshot.value.copy(config = config(wifiState = "connecting"))
        advanceTimeBy(DeviceConfigController.WIFI_POLL_MS * 2 + 1)
        assertEquals(listOf("refresh", "refresh"), source.calls)
        source.snapshot.value = source.snapshot.value.copy(config = config(wifiState = "connected"))
        advanceTimeBy(DeviceConfigController.WIFI_POLL_MS * 5)
        assertEquals(2, source.calls.size)
    }

    @Test
    fun wifiPollStopsAfterItsAttempts() = runTest {
        val source = FakeSource()
        controller(source)
        source.snapshot.value = source.snapshot.value.copy(config = config(wifiState = "connecting"))
        advanceTimeBy(DeviceConfigController.WIFI_POLL_MS * (DeviceConfigController.WIFI_POLL_ATTEMPTS + 5))
        assertEquals(DeviceConfigController.WIFI_POLL_ATTEMPTS, source.calls.size)
    }

    @Test
    fun stickerIsRecordedOnlyAfterTheBoardConfirmsIt() = runTest {
        val source = FakeSource()
        val c = controller(source)
        val notices = collectNotices(c)
        val sticker = "PMS5003T-202604081332"
        source.sensorAnswer = config(sensor = SensorConfig("Plantower", "PMS5003T", sticker, 1))
        c.saveSensor(sticker, batchEstimate = true, stickerSource = STICKER_SOURCE_MANUAL)
        assertEquals(listOf("sensor sensor.vendor,sensor.model,sensor.serial,sensor.batch_candidate"), source.calls)
        assertEquals(listOf("Plantower PMS5003T $sticker PMS5003T-20260408 manual"), source.recorded)
        assertEquals(listOf<DeviceConfigNotice>(DeviceConfigNotice.SensorSaved(true)), notices)
    }

    @Test
    fun stickerTheBoardDidNotEchoIsNotRecorded() = runTest {
        val source = FakeSource()
        val c = controller(source)
        val notices = collectNotices(c)
        source.sensorAnswer = config(sensor = SensorConfig("Plantower", "PMS5003T", "PMS5003T-202604081333", 0))
        c.saveSensor("PMS5003T-202604081332", batchEstimate = false, stickerSource = STICKER_SOURCE_BARCODE)
        assertEquals(emptyList(), source.recorded)
        assertEquals(listOf<DeviceConfigNotice>(DeviceConfigNotice.SensorNotConfirmed), notices)
    }

    @Test
    fun scannedStickerGoesThroughTheSameParserAndModelCheck() = runTest {
        val source = FakeSource()
        val c = controller(source)
        val notices = collectNotices(c)
        val sticker = "PMS5003T-202604081332"
        source.sensorAnswer = config(sensor = SensorConfig("Plantower", "PMS5003T", sticker, 0))
        val scanned = StickerScans.read(null, " $sticker ", "unrecognized")?.value ?: error("not parsed")
        c.saveSensor(scanned, batchEstimate = false, stickerSource = STICKER_SOURCE_BARCODE)
        assertEquals(listOf("Plantower PMS5003T $sticker PMS5003T-20260408 barcode"), source.recorded)
        // A scanned sticker of another model parses, and the model check still refuses it.
        val other = StickerScans.read(null, "PMS5003-202604081332", "unrecognized")?.value ?: error("not parsed")
        c.saveSensor(other, batchEstimate = false, stickerSource = STICKER_SOURCE_BARCODE)
        assertEquals(1, source.recorded.size)
        assertEquals(listOf(DeviceConfigNotice.SensorSaved(false), DeviceConfigNotice.StickerInvalid), notices)
    }

    @Test
    fun stickerSaveFailureIsReported() = runTest {
        val source = FakeSource()
        val c = controller(source)
        val notices = collectNotices(c)
        c.saveSensor("PMS5003T-202604081332", batchEstimate = false, stickerSource = STICKER_SOURCE_MANUAL)
        assertEquals(emptyList(), source.recorded)
        assertEquals(listOf<DeviceConfigNotice>(DeviceConfigNotice.SensorFailed), notices)
    }

    @Test
    fun unsupportedOrMalformedStickerIsNotSent() = runTest {
        val source = FakeSource()
        val c = controller(source)
        val notices = collectNotices(c)
        c.saveSensor("PMS5003-202604081332", batchEstimate = false, stickerSource = STICKER_SOURCE_MANUAL)
        c.saveSensor("not a sticker", batchEstimate = false, stickerSource = STICKER_SOURCE_MANUAL)
        assertEquals(emptyList(), source.calls)
        assertEquals(listOf<DeviceConfigNotice>(DeviceConfigNotice.StickerInvalid, DeviceConfigNotice.StickerInvalid), notices)
    }

    @Test
    fun stickerIsRefusedDuringASync() = runTest {
        val source = FakeSource(syncRunning = true)
        val c = controller(source)
        val notices = collectNotices(c)
        c.saveSensor("PMS5003T-202604081332", batchEstimate = false, stickerSource = STICKER_SOURCE_MANUAL)
        assertEquals(emptyList(), source.calls)
        assertEquals(listOf<DeviceConfigNotice>(DeviceConfigNotice.SensorBusy), notices)
    }

    @Test
    fun platformActionsAreLeftToThePlatform() = runTest {
        val source = FakeSource()
        val c = controller(source)
        assertFalse(c.onAction(DeviceConfigAction.RequestLanAccess))
        assertFalse(c.onAction(DeviceConfigAction.ScanSticker))
        assertTrue(c.onAction(DeviceConfigAction.RequestReboot))
        assertEquals(DeviceConfigDialog.Reboot, c.dialog.value)
        assertEquals(emptyList(), source.calls)
    }

    @Test
    fun secretsNeverAppearInToString() {
        val pairing = PairingChoice(PairMode.FIXED, SECRET_PIN)
        val join = WifiJoin("home", SECRET_PSK, open = false)
        val action = DeviceConfigAction.JoinWifi(join)
        val savePairing = DeviceConfigAction.SavePairing(pairing)
        val snapshot = config()
        val state = DeviceConfigUiState(DeviceConfigSnapshot(config = snapshot, hasLanToken = true))
        val patch = ConfigPatch.build {
            blePin(SECRET_PIN)
            wifiPsk(SECRET_PSK)
        }
        for (text in listOf(pairing, join, action, savePairing, snapshot, state, patch, snapshot.config.ble).map { it.toString() }) {
            assertFalse(SECRET_PIN in text, text)
            assertFalse(SECRET_PSK in text, text)
            assertFalse("123456" in text, text)
        }
        assertTrue("home" in join.toString())
    }

    @Test
    fun redactedHoldersStillCompareByValue() {
        assertEquals(PairingChoice(PairMode.FIXED, SECRET_PIN), PairingChoice(PairMode.FIXED, SECRET_PIN))
        assertFalse(PairingChoice(PairMode.FIXED, SECRET_PIN) == PairingChoice(PairMode.FIXED, "000000"))
        assertEquals(WifiJoin("home", SECRET_PSK, false), WifiJoin("home", SECRET_PSK, false))
        assertFalse(WifiJoin("home", SECRET_PSK, false) == WifiJoin("home", "other passphrase", false))
    }
}
