package space.opensensor.device

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import space.opensensor.data.ble.ConfigSnapshot
import space.opensensor.data.ble.ConnectionState
import space.opensensor.data.ble.TransportKind
import space.opensensor.data.history.DeviceCapabilities
import space.opensensor.data.protocol.BleConfig
import space.opensensor.data.protocol.ConfigFields
import space.opensensor.data.protocol.ConfigPatch
import space.opensensor.data.protocol.ConfigProblem
import space.opensensor.data.protocol.Confirm
import space.opensensor.data.protocol.DeviceConfig
import space.opensensor.data.protocol.DeviceInfo
import space.opensensor.data.protocol.LanConfig
import space.opensensor.data.protocol.LocationConfig
import space.opensensor.data.protocol.PairMode
import space.opensensor.data.protocol.SensorConfig
import space.opensensor.data.protocol.WifiConfig
import space.opensensor.model.SiteField
import space.opensensor.model.SiteProblem
import space.opensensor.model.ThingSite
import space.opensensor.settings.DetailLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SECRET_PIN = "482913"
private const val SECRET_PSK = "correct horse battery"
private const val CELL8 = "8828308281fffff"

// The bundled Waveshare template has the sensor_serial_code column, so the board stores a sticker.
private val v2Info = DeviceInfo(
    proto = 3,
    schema = "waveshare-sim7670g-telemetry-v5",
    dict = "a8605aafb6f76b794e9e7fb32927be6d203afeeb14527206c0e49c1eb0436809",
)

private fun config(wifiState: String = "off", sensor: SensorConfig? = null) = ConfigSnapshot(
    config = DeviceConfig(
        ble = BleConfig(pair = "random", pinSet = 1, pinDefault = 0, bonds = 1, display = 1),
        wifi = WifiConfig(on = 1, ssid = "home", pskSet = 1, state = wifiState),
        lan = LanConfig(on = 1, port = 47390, host = "os-6b40", clients = 0),
        sensor = sensor,
    ),
    rebootRequired = false,
    rawJson = "{\"ble\":{\"pin_set\":1}}",
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
    var locationAnswer: LocationConfig? = null
    override suspend fun readLocation(): LocationConfig? = locationAnswer
    override suspend fun writeLocation(patch: ConfigPatch): LocationConfig? {
        wire += patch.encode()
        return locationAnswer
    }
    val recorded = mutableListOf<String>()

    /** Pages 5 and 6 as the board answers them. Null is firmware without site keys. */
    var siteAnswer: ThingSite? = null
    var siteError: Exception? = null
    var siteWriteError: Exception? = null
    var siteReads = 0
    val sitePatches = mutableListOf<ConfigPatch>()

    override suspend fun readSite(): ThingSite? {
        siteReads++
        siteError?.let { throw it }
        return siteAnswer
    }

    override suspend fun writeSite(patch: ConfigPatch): ThingSite? {
        sitePatches += patch
        wire += patch.encode()
        siteWriteError?.let { throw it }
        return siteAnswer
    }

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
    fun aJoinStillConnectingAfterThePollSaysSoOnce() = runTest {
        val source = FakeSource()
        val c = controller(source)
        val notices = collectNotices(c)
        source.snapshot.value = source.snapshot.value.copy(config = config(wifiState = "connecting"))
        advanceTimeBy(DeviceConfigController.WIFI_POLL_MS * (DeviceConfigController.WIFI_POLL_ATTEMPTS + 5))
        assertEquals(listOf<DeviceConfigNotice>(DeviceConfigNotice.WifiStillJoining), notices)
    }

    @Test
    fun everyConfirmedKeyInTheTableHasADialog() {
        for (field in ConfigFields.ALL) {
            when (val confirm = field.confirm) {
                Confirm.Never -> continue
                Confirm.Always -> assertNotNull(DeviceConfigDialog.forField(field.key, "1"), field.key)
                is Confirm.When -> assertNotNull(DeviceConfigDialog.forField(field.key, confirm.value), field.key)
            }
        }
        for (dialog in DeviceConfigDialog.FIELDS) assertTrue(dialog.field.needsConfirm(dialog.value), dialog.key)
        assertNull(DeviceConfigDialog.forField(ConfigFields.BLE_PAIR, "random"))
        assertNull(DeviceConfigDialog.forField(ConfigFields.WIFI_ON, "1"))
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

    @Test
    fun locationWritesRequireSupportAndMatchingReadback() = runTest {
        val source = FakeSource()
        source.locationAnswer = LocationConfig(cell = "", resolution = 8, country = "")
        val c = controller(source)
        c.acceptLocationCell(CELL8, 8)
        c.selectLocationCountry("us")
        source.locationAnswer = LocationConfig(cell = CELL8, resolution = 8, country = "US")
        c.saveLocation()
        assertEquals(listOf("location.cell=$CELL8\nlocation.resolution=8\nlocation.country=US\n"), source.wire)
        assertEquals(DeviceLocationResult.SAVED, c.state.value.location.result)
        c.selectLocationCountry("CA")
        c.saveLocation()
        assertEquals(DeviceLocationResult.NOT_CONFIRMED, c.state.value.location.result)
    }

    @Test
    fun onlyAPickedResolution8CellReachesTheWire() = runTest {
        val source = FakeSource()
        source.locationAnswer = LocationConfig(cell = "", resolution = 8, country = "")
        val c = controller(source)
        // A coordinate, a finer or coarser cell, or another resolution is never drafted.
        c.acceptLocationCell("5.5600,-0.2000", 8)
        c.acceptLocationCell("8928308280fffff", 8)
        c.acceptLocationCell("85283083fffffff", 8)
        c.acceptLocationCell("872830828ffffff", 8)
        c.acceptLocationCell(CELL8, 9)
        assertEquals("", c.state.value.location.draft.cell)
        c.acceptLocationCell(CELL8, 8)
        assertEquals(CELL8, c.state.value.location.draft.cell)
        c.selectLocationCountry("gh")
        assertEquals(emptyList(), source.wire, "a pick alone sends nothing")
        c.saveLocation()
        assertEquals(listOf("location.cell=$CELL8\nlocation.resolution=8\nlocation.country=GH\n"), source.wire)
    }

    @Test
    fun onlyAConfirmedValidCellCountsAsSaved() = runTest {
        val source = FakeSource()
        source.locationAnswer = LocationConfig(cell = "", resolution = 8, country = "GH")
        val c = controller(source)
        assertFalse(c.state.value.location.saved)
        c.acceptLocationCell(CELL8, 8)
        assertFalse(c.state.value.location.saved)
        source.locationAnswer = LocationConfig(cell = CELL8, resolution = 8, cellResolution = 8, country = "")
        c.saveLocation()
        assertTrue(c.state.value.location.saved)
        c.clearLocation()
        assertTrue(c.state.value.location.saved)
        source.locationAnswer = LocationConfig(cell = "", resolution = 8, country = "")
        c.saveLocation()
        assertFalse(c.state.value.location.saved)
    }

    @Test
    fun aCellFinerThanTheMaximumOrBadHexIsNoLocation() {
        assertTrue(LocationConfig(cell = "85283083fffffff", resolution = 8).hasCell())
        assertFalse(LocationConfig(cell = "8928308280fffff", resolution = 5).hasCell())
        assertFalse(LocationConfig(cell = "85283083fffffff", resolution = 5, cellResolution = 9).hasCell())
        assertFalse(LocationConfig(cell = "nothex", resolution = 5).hasCell())
        assertFalse(LocationConfig(cell = null, country = "GH").hasCell())
        assertFalse(LocationConfig(cell = "000000000000000", resolution = 8).hasCell(), "hex that is no H3 cell")
    }

    @Test
    fun unsupportedBusyOrCoarserWithoutFreshFixCannotWriteLocation() = runTest {
        val old = FakeSource()
        val unsupported = controller(old)
        unsupported.saveLocation()
        assertEquals(emptyList(), old.wire)
        val source = FakeSource()
        // A res 5 cell kept from before the res 8 rule cannot be refined without a fresh fix.
        source.locationAnswer = LocationConfig(cell = "85283083fffffff", resolution = 5, country = "US")
        val c = controller(source)
        assertTrue(c.state.value.location.draft.needsFix)
        c.saveLocation()
        assertEquals(emptyList(), source.wire)
        c.acceptLocationCell(CELL8, 8)
        source.snapshot.value = source.snapshot.value.copy(syncRunning = true)
        c.saveLocation()
        assertEquals(emptyList(), source.wire)
    }

    // ------------------------------------------------------------------ owner site (#272)

    private val roof = ThingSite(title = "Roof", provider = "", url = "", description = "", deploymentId = "accra-1")

    @Test
    fun siteFieldsShowOnlyWhenPage5AnswersAndAreHiddenOnError1() = runTest {
        val supported = FakeSource().apply { siteAnswer = roof }
        val c = controller(supported)
        assertEquals(true, c.state.value.site.supported)
        assertEquals(roof, c.state.value.site.draft)
        assertEquals(1, supported.siteReads, "read once when the link turns ready")
        val legacy = FakeSource()
        assertEquals(false, controller(legacy).state.value.site.supported, "page 5 ERROR 1 hides the fields")
    }

    @Test
    fun aPage6FailureAfterPage5IsAnErrorNotUnsupported() = runTest {
        val source = FakeSource().apply { siteError = IllegalStateException("page 6 ERROR 1") }
        val c = controller(source)
        advanceTimeBy(1_000)
        assertEquals(null, c.state.value.site.supported)
        assertEquals(DeviceSiteResult.FAILED, c.state.value.site.result)
        assertFalse(c.state.value.site.canSave)
        assertEquals(3, source.siteReads, "a failed read is tried three times in all")
    }

    @Test
    fun aSiteReadThatMeetsABusyLinkIsTriedAgain() = runTest {
        val source = FakeSource().apply {
            siteAnswer = roof
            siteError = IllegalStateException("link busy")
        }
        val c = controller(source)
        assertEquals(null, c.state.value.site.result, "no failure while tries remain")
        source.siteError = null
        advanceTimeBy(1_000)
        assertEquals(true, c.state.value.site.supported)
        assertEquals(roof, c.state.value.site.draft)
        assertEquals(2, source.siteReads)
    }

    @Test
    fun typingNeverWritesOnlySaveDoes() = runTest {
        val source = FakeSource().apply { siteAnswer = roof }
        val c = controller(source)
        c.onAction(DeviceConfigAction.SiteValue(SiteField.TITLE, "School roof"))
        c.onAction(DeviceConfigAction.SiteValue(SiteField.URL, "https://opensensor.space"))
        assertEquals(emptyList(), source.wire, "a draft sends nothing")
        assertTrue(c.state.value.site.dirty)
        source.siteAnswer = roof.copy(title = "School roof", url = "https://opensensor.space")
        c.onAction(DeviceConfigAction.SaveSite)
        assertEquals(listOf("owner.title=School roof\nowner.url=https://opensensor.space\n"), source.wire)
        assertEquals(DeviceSiteResult.SAVED, c.state.value.site.result)
        assertFalse(c.state.value.site.dirty)
    }

    @Test
    fun anEmptiedValueIsSentAndAnUnchangedEmptyOneIsNot() = runTest {
        val source = FakeSource().apply { siteAnswer = roof }
        val c = controller(source)
        c.editSite(SiteField.DEPLOYMENT_ID, "")
        c.editSite(SiteField.PROVIDER, "")
        source.siteAnswer = roof.copy(deploymentId = "")
        c.saveSite()
        assertEquals(listOf("thing.deployment_id=\n"), source.wire)
        assertTrue(source.sitePatches.single().isSite, "site keys go in their own SET_CONFIG")
        assertEquals(listOf(ConfigFields.THING_DEPLOYMENT_ID), source.sitePatches.single().keys)
    }

    @Test
    fun anInvalidOrUnchangedDraftSendsNothing() = runTest {
        val source = FakeSource().apply { siteAnswer = roof }
        val c = controller(source)
        c.saveSite()
        assertEquals(emptyList(), source.wire, "nothing changed")
        c.editSite(SiteField.URL, "http://opensensor.space")
        assertEquals(mapOf(SiteField.URL to SiteProblem.SCHEME), c.state.value.site.problems)
        assertFalse(c.state.value.canSaveSite)
        c.saveSite()
        c.editSite(SiteField.URL, "https://opensensor.space")
        c.editSite(SiteField.DEPLOYMENT_ID, "unknown")
        c.saveSite()
        assertEquals(emptyList(), source.wire)
    }

    @Test
    fun siteSaveNeedsAnEditableIdleLinkAndASupportedBoard() = runTest {
        val legacy = FakeSource()
        val unsupported = controller(legacy)
        unsupported.editSite(SiteField.TITLE, "Roof")
        unsupported.saveSite()
        assertEquals(emptyList(), legacy.wire)

        val source = FakeSource().apply { siteAnswer = roof }
        val c = controller(source)
        c.editSite(SiteField.TITLE, "School")
        source.snapshot.value = source.snapshot.value.copy(syncRunning = true)
        c.saveSite()
        assertEquals(emptyList(), source.wire, "not during a sync")
        source.snapshot.value = source.snapshot.value.copy(syncRunning = false, connection = ConnectionState.Disconnected)
        c.saveSite()
        assertEquals(emptyList(), source.wire, "not without a link")
    }

    @Test
    fun aReadbackThatDiffersOrIsMissingIsNotConfirmed() = runTest {
        val source = FakeSource().apply { siteAnswer = roof }
        val c = controller(source)
        c.editSite(SiteField.TITLE, "School")
        source.siteAnswer = roof
        c.saveSite()
        assertEquals(DeviceSiteResult.NOT_CONFIRMED, c.state.value.site.result)
        assertEquals("School", c.state.value.site.draft.title, "the draft is kept to try again")
        source.siteAnswer = null
        c.saveSite()
        assertEquals(DeviceSiteResult.NOT_CONFIRMED, c.state.value.site.result)
    }

    @Test
    fun aRefusedSiteWriteFailsAndTheNextSuccessReadsEveryPageAgain() = runTest {
        val source = FakeSource().apply { siteAnswer = roof }
        val c = controller(source)
        source.calls.clear()
        c.editSite(SiteField.TITLE, "School")
        source.siteWriteError = IllegalStateException("ERROR 12 nvs")
        c.saveSite()
        assertEquals(DeviceSiteResult.FAILED, c.state.value.site.result)
        assertEquals("School", c.state.value.site.draft.title)
        assertEquals(emptyList(), source.calls, "no re-read yet")
        source.siteWriteError = null
        source.siteAnswer = roof.copy(title = "School")
        c.saveSite()
        assertEquals(DeviceSiteResult.SAVED, c.state.value.site.result)
        assertEquals(listOf("refresh"), source.calls, "page 0 is read again after the next success")
        c.editSite(SiteField.TITLE, "Roof")
        source.siteAnswer = roof
        c.saveSite()
        assertEquals(listOf("refresh"), source.calls, "only once")
    }
}
