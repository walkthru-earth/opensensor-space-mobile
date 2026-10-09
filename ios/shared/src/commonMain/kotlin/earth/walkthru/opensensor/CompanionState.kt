package earth.walkthru.opensensor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import earth.walkthru.aqsync.data.history.NowReading
import earth.walkthru.aqsync.data.protocol.AqGatt
import earth.walkthru.aqsync.data.protocol.DeviceInfo
import earth.walkthru.aqsync.data.protocol.DeviceStatus
import earth.walkthru.aqsync.data.protocol.LanProtocol
import earth.walkthru.aqsync.data.protocol.LiveProfiles
import earth.walkthru.aqsync.data.protocol.LiveSample
import earth.walkthru.opensensor.permissions.AccessStatus
import earth.walkthru.opensensor.permissions.IosAuthorization
import earth.walkthru.opensensor.strings.UiText
import earth.walkthru.opensensor.strings.resources.Res
import earth.walkthru.opensensor.strings.resources.companion_connecting_pin
import earth.walkthru.opensensor.strings.resources.companion_info_invalid
import earth.walkthru.opensensor.strings.resources.companion_lan_denied
import earth.walkthru.opensensor.strings.resources.companion_lan_found
import earth.walkthru.opensensor.strings.resources.companion_lan_looking
import earth.walkthru.opensensor.strings.resources.companion_lan_none
import earth.walkthru.opensensor.strings.resources.companion_lan_unavailable
import earth.walkthru.opensensor.strings.resources.companion_lan_waiting
import earth.walkthru.opensensor.strings.resources.companion_live
import earth.walkthru.opensensor.strings.resources.companion_live_unreadable
import earth.walkthru.opensensor.strings.resources.companion_status_unreadable
import earth.walkthru.opensensor.strings.resources.now_waiting
import kotlin.time.Clock

internal data class NearbyDevice(val id: String, val name: String, val rssi: Int)

/**
 * Main-thread callbacks from the native BLE adapter. Never contains pairing or Wi-Fi secrets.
 * Messages are [UiText]. The state picks a `:ui:strings` key and the screen resolves the words.
 * Link, Bluetooth and Wi-Fi access events arrive as a [CompanionNotice] or a [SyncResult], so Swift
 * never words what the user reads. The String overloads stay internal for the shared tests.
 */
class CompanionState {
    internal var devices by mutableStateOf<List<NearbyDevice>>(emptyList())
        private set
    internal var scanning by mutableStateOf(false)
        private set
    internal var bluetoothAvailable by mutableStateOf(false)
        private set

    /** Bluetooth authorization as the shared policy reads it. Not determined until the first tap. */
    internal var bluetoothAccess by mutableStateOf(AccessStatus.NOT_DETERMINED)
        private set

    /** True only while the central manager reports the radio powered off. */
    internal var bluetoothPoweredOff by mutableStateOf(false)
        private set
    internal var message by mutableStateOf<UiText>(UiText.None)
        private set
    internal var selectedId by mutableStateOf<String?>(null)
        private set
    internal var connected by mutableStateOf(false)
        private set
    internal var info by mutableStateOf<DeviceInfo?>(null)
        private set
    internal var status by mutableStateOf<DeviceStatus?>(null)
        private set

    /** The last STATUS body as it arrived, for the Debug screen, and when it arrived. */
    internal var statusRaw by mutableStateOf<String?>(null)
        private set
    internal var statusAtEpochMs by mutableStateOf<Long?>(null)
        private set
    internal var live by mutableStateOf<LiveSample?>(null)
        private set
    internal val reading: NowReading get() = NowReading.of(live)
    internal val profile get() = LiveProfiles.resolve(info)

    internal var lanDevices by mutableStateOf<List<String>>(emptyList())
        private set

    /** The station of each Wi-Fi service name, from its TXT record. */
    internal var lanStations by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    internal var lanScanning by mutableStateOf(false)
        private set
    internal var lanMessage by mutableStateOf<UiText>(UiText.None)
        private set

    /** True only after iOS reports the Local Network policy denial (TN3179), never for plain waiting. */
    internal var lanAccessDenied by mutableStateOf(false)
        private set

    /** Local network has no status API on iOS, so it stays unknown until the policy denial. */
    internal val lanAccess: AccessStatus get() = IosAuthorization.localNetwork(policyDenied = lanAccessDenied)

    internal var wifiAccessAvailable by mutableStateOf(false)
        private set
    internal var wifiAccessBusy by mutableStateOf(false)
        private set
    internal var wifiAccessMessage by mutableStateOf<UiText>(UiText.None)
        private set

    /** How the last Wi-Fi access request ended, cleared when a new one starts. Device settings reads it. */
    internal var wifiAccessNotice by mutableStateOf<CompanionNotice?>(null)
        private set
    internal var lanSessions by mutableStateOf<Map<String, LanSessionState>>(emptyMap())
        private set

    /** Narrow Swift bridge for an already authenticated Wi-Fi session. */
    fun isLanConnected(name: String): Boolean = lanSessions[name]?.connected == true
    internal var lanPaused by mutableStateOf<Set<String>>(emptySet())
        private set

    /** File collection only, distinct from a busy LAN handshake or a short command. */
    internal var lanRuns by mutableStateOf<Map<String, LanRunState>>(emptyMap())
        private set

    fun lanSyncStarted(name: String) {
        lanRuns = lanRuns + (
            name to LanRunState(
                station = lanInfo[name]?.station,
                running = true,
                startedAtEpochMs = Clock.System.now().toEpochMilliseconds(),
            )
            )
    }

    fun lanSyncStopped(name: String) {
        val run = lanRuns[name]?.takeIf { it.running } ?: return
        lanRuns = lanRuns + (name to run.copy(running = false, interrupted = true, finishedAtEpochMs = Clock.System.now().toEpochMilliseconds()))
    }

    internal fun connectedLanForStation(station: String?): String? =
        station?.let { wanted ->
            lanInfo.entries.firstOrNull { (name, info) ->
                info.station == wanted && lanSessions[name]?.connected == true
            }?.key
        }
    fun lanCollectionPaused(name: String, paused: Boolean) {
        lanPaused = if (paused) lanPaused + name else lanPaused - name
    }

    internal fun wifiAccessChanged(available: Boolean, busy: Boolean, explanation: String) {
        wifiAccessAvailable = available
        wifiAccessBusy = busy
        wifiAccessMessage = UiText.plain(explanation)
        wifiAccessNotice = null
    }

    /** The Bluetooth connection is gone, so Wi-Fi access cannot be requested and shows nothing. */
    fun wifiAccessReset() {
        wifiAccessChanged(available = false, busy = false, explanation = "")
    }

    /** A Wi-Fi access request started. The last outcome clears until this one ends. */
    fun wifiAccessSaving() {
        wifiAccessAvailable = true
        wifiAccessBusy = true
        wifiAccessMessage = CompanionNotice.SAVING_WIFI_ACCESS.text
        wifiAccessNotice = null
    }

    /** Wi-Fi access state with a typed [notice] in Android's words. */
    fun wifiAccessNoticed(available: Boolean, busy: Boolean, notice: CompanionNotice) {
        wifiAccessAvailable = available
        wifiAccessBusy = busy
        wifiAccessMessage = notice.text
        wifiAccessNotice = notice
    }

    /** Native sessions are independent: a callback updates only its own sensor card. */
    internal fun lanSessionChanged(name: String, connected: Boolean, busy: Boolean, message: String, filesDone: Int, filesTotal: Int, bytesDone: Long, bytesPerSecond: Double = 0.0, bytesTotal: Long = 0) {
        lanSession(name, connected, busy, UiText.plain(message), filesDone, filesTotal, bytesDone, bytesPerSecond, bytesTotal)
    }

    /** Native sessions are independent: a callback updates only its own sensor card, worded by [notice]. */
    fun lanSessionNoticed(name: String, connected: Boolean, busy: Boolean, notice: CompanionNotice, filesDone: Int, filesTotal: Int, bytesDone: Long, bytesPerSecond: Double, bytesTotal: Long = 0) {
        lanSession(name, connected, busy, notice.text, filesDone, filesTotal, bytesDone, bytesPerSecond, bytesTotal)
    }

    /** A Wi-Fi sync ended. The card keeps its counts and shows the outcome. */
    fun lanSyncFinished(name: String, connected: Boolean, result: SyncResult) {
        val previousRun = lanRuns[name]
        lanRuns = lanRuns + (
            name to LanRunState(
                station = previousRun?.station ?: lanInfo[name]?.station,
                running = false,
                startedAtEpochMs = previousRun?.startedAtEpochMs,
                finishedAtEpochMs = Clock.System.now().toEpochMilliseconds(),
                result = result,
                interrupted = false,
            )
            )
        val previous = lanSessions[name]
        lanSession(name, connected, false, result.message, previous?.filesDone ?: 0, previous?.filesTotal ?: 0, previous?.bytesDone ?: 0, 0.0, previous?.bytesTotal ?: 0)
    }

    private fun lanSession(name: String, connected: Boolean, busy: Boolean, message: UiText, filesDone: Int, filesTotal: Int, bytesDone: Long, bytesPerSecond: Double, bytesTotal: Long) {
        val rate = bytesPerSecond.takeIf { connected && busy && it.isFinite() && it > 0.0 } ?: 0.0
        lanSessions = lanSessions + (name to LanSessionState(connected, busy, message, filesDone, filesTotal, bytesDone, rate, bytesTotal))
        if (!connected) {
            lanReadings = lanReadings - name
            lanInfo = lanInfo - name
            lanStatus = lanStatus - name
            lanStatusRaw = lanStatusRaw - name
            lanStatusAtEpochMs = lanStatusAtEpochMs - name
            lanLive = lanLive - name
        }
    }

    /** Bluetooth file sync for the connected sensor. Available once INFO names its station. */
    internal var syncAvailable by mutableStateOf(false)
        private set
    internal var syncBusy by mutableStateOf(false)
        private set
    internal var syncProgress by mutableStateOf<SyncProgress?>(null)
        private set
    internal var syncMessage by mutableStateOf<UiText>(UiText.None)
        private set

    /** How the last Bluetooth sync of this connection ended, for the Smart Sync card of Today. */
    internal var lastSyncResult by mutableStateOf<SyncResult?>(null)
        private set

    /** SET_TIME and FLUSH from Today, over the same Bluetooth connection. */
    val today = TodayLink()

    fun bluetoothSyncAvailable(available: Boolean) {
        syncAvailable = available && connected
    }

    fun bluetoothSyncStarted() {
        syncBusy = true
        syncProgress = null
        syncMessage = UiText.None
    }

    fun bluetoothSyncProgressed(progress: SyncProgress) {
        if (syncBusy) syncProgress = progress
    }

    fun bluetoothSyncFinished(result: SyncResult) {
        syncBusy = false
        lastSyncResult = result
        syncMessage = result.message
    }

    /** A Bluetooth sync ended with the link gone. The connection closes and shows the outcome. */
    fun bluetoothSyncClosed(result: SyncResult) {
        close(result.message)
    }

    internal var lanReadings by mutableStateOf<Map<String, NowReading>>(emptyMap())
        private set

    /** Full verified HELLO and the latest pushes for each authenticated Wi-Fi session. */
    internal var lanInfo by mutableStateOf<Map<String, DeviceInfo>>(emptyMap())
        private set
    internal var lanStatus by mutableStateOf<Map<String, DeviceStatus>>(emptyMap())
        private set
    internal var lanStatusRaw by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    internal var lanStatusAtEpochMs by mutableStateOf<Map<String, Long>>(emptyMap())
        private set
    internal var lanLive by mutableStateOf<Map<String, LiveSample>>(emptyMap())
        private set

    fun lanInfoReceived(name: String, json: String) {
        if (lanSessions[name]?.connected != true) return
        val parsed = runCatching { DeviceInfo.parse(json) }.getOrNull() ?: return
        if (parsed.proto != 2 || parsed.dev.isNullOrBlank() || parsed.station.isNullOrBlank()) return
        lanInfo = lanInfo + (name to parsed)
    }

    fun lanStatusReceived(name: String, json: String) {
        if (lanSessions[name]?.connected != true) return
        val parsed = runCatching { DeviceStatus.parse(json) }.getOrNull()
        lanStatus = if (parsed == null) lanStatus - name else lanStatus + (name to parsed)
        lanStatusRaw = if (parsed == null) lanStatusRaw - name else lanStatusRaw + (name to json)
        lanStatusAtEpochMs = if (parsed == null) {
            lanStatusAtEpochMs - name
        } else {
            lanStatusAtEpochMs + (name to Clock.System.now().toEpochMilliseconds())
        }
    }

    fun lanReadingReceived(name: String, json: String) {
        if (lanSessions[name]?.connected != true) return
        val sample = runCatching { LiveSample.parse(json) }.getOrNull()
        lanLive = if (sample == null) lanLive - name else lanLive + (name to sample)
        lanReadings = lanReadings + (name to NowReading.of(sample))
    }

    fun lanReadingExpired(name: String) {
        lanReadings = lanReadings - name
        lanLive = lanLive - name
    }

    // Export strings through this facade; platform adapters do not duplicate the wire contract.
    val lanServiceType: String get() = LanProtocol.SERVICE_TYPE
    val serviceUuid: String get() = AqGatt.SERVICE
    val infoUuid: String get() = AqGatt.INFO
    val liveUuid: String get() = AqGatt.LIVE
    val statusUuid: String get() = AqGatt.STATUS

    fun lanScanStarted() {
        lanAccessDenied = false
        lanDevices = emptyList()
        lanStations = emptyMap()
        lanScanning = true
        lanMessage = UiText.of(Res.string.companion_lan_looking)
    }

    /** Like [lanDevicesFound], with the TXT station of each name, in the same order. */
    fun lanSensorsFound(names: List<String>, stations: List<String>) {
        if (!lanScanning) return
        lanStations = names.zip(stations).toMap()
        lanDevicesFound(names)
    }

    fun lanDevicesFound(names: List<String>) {
        if (!lanScanning) return
        lanDevices = names.distinct().sorted()
        lanMessage = if (lanDevices.isEmpty()) UiText.of(Res.string.companion_lan_looking) else UiText.of(Res.string.companion_lan_found)
    }

    fun lanScanStopped() {
        if (!lanScanning) return
        lanScanning = false
        if (lanDevices.isEmpty()) lanMessage = UiText.of(Res.string.companion_lan_none)
    }

    fun lanScanWaiting() {
        if (!lanScanning) return
        lanAccessDenied = false
        lanMessage = UiText.of(Res.string.companion_lan_waiting)
    }

    /** The browse keeps running, so turning access on in Settings resumes discovery without a restart. */
    fun lanScanAccessDenied() {
        if (!lanScanning) return
        lanAccessDenied = true
        lanMessage = UiText.of(Res.string.companion_lan_denied)
    }

    fun lanScanReady() {
        lanAccessDenied = false
        if (lanScanning && lanDevices.isEmpty()) lanMessage = UiText.of(Res.string.companion_lan_looking)
    }

    fun lanScanUnavailable() {
        lanScanning = false
        lanDevices = emptyList()
        lanStations = emptyMap()
        lanMessage = UiText.of(Res.string.companion_lan_unavailable)
    }

    /** The raw `CBManager.authorization` value, read when the central manager reports its state. */
    fun bluetoothAuthorizationChanged(raw: Long) {
        bluetoothAccess = IosAuthorization.bluetooth(raw)
    }

    /** The radio state from the central manager. Off means the user can turn it on in Control Center. */
    fun bluetoothPowerChanged(poweredOff: Boolean) {
        bluetoothPoweredOff = poweredOff
    }

    internal fun bluetoothChanged(available: Boolean, explanation: String) {
        bluetooth(available, UiText.plain(explanation))
    }

    /** The central manager state. A null [notice] means nothing to say, as when the radio is on. */
    fun bluetoothNoticed(available: Boolean, notice: CompanionNotice?) {
        bluetooth(available, notice?.text ?: UiText.None)
    }

    private fun bluetooth(available: Boolean, explanation: UiText) {
        bluetoothAvailable = available
        message = explanation
        if (!available) {
            scanning = false
            close(explanation)
        }
    }

    /** My devices draws the scan itself, so a scan leaves the device page's message alone. */
    fun scanStarted() {
        devices = emptyList()
        scanning = true
    }

    fun scanStopped() {
        scanning = false
    }

    fun deviceFound(id: String, name: String, rssi: Int) {
        val device = NearbyDevice(id, name, rssi)
        devices = (devices.filterNot { it.id == id } + device).sortedByDescending { it.rssi }
    }

    fun connectionStarted(id: String) {
        selectedId = id
        connected = false
        scanning = false
        info = null
        status = null
        live = null
        message = UiText.of(Res.string.companion_connecting_pin)
    }

    /** Returns false for malformed or unsupported INFO; the adapter must close that connection. */
    fun receiveInfo(json: String): Boolean {
        val decoded = runCatching { DeviceInfo.parse(json) }.getOrNull()
        if (decoded == null || decoded.proto !in 1..AqGatt.PROTOCOL_VERSION || decoded.dev.isNullOrBlank()) {
            close(UiText.of(Res.string.companion_info_invalid))
            return false
        }
        info = decoded
        connected = true
        message = UiText.of(Res.string.companion_live)
        return true
    }

    fun receiveStatus(json: String) {
        if (!connected) return
        statusRaw = json
        statusAtEpochMs = Clock.System.now().toEpochMilliseconds()
        status = runCatching { DeviceStatus.parse(json) }.getOrNull()
        if (status == null) message = UiText.of(Res.string.companion_status_unreadable)
    }

    fun receiveLive(json: String) {
        if (!connected) return
        // A bad notification must not leave an earlier number looking current.
        live = runCatching { LiveSample.parse(json) }.getOrNull()
        message = if (live == null) UiText.of(Res.string.companion_live_unreadable) else UiText.of(Res.string.companion_live)
    }

    fun readingExpired() {
        live = null
        if (connected) message = UiText.of(Res.string.now_waiting)
    }

    internal fun connectionClosed(explanation: String) {
        close(UiText.plain(explanation))
    }

    /** The Bluetooth connection ended for a typed [notice], shown in Android's words. */
    fun connectionClosedNoticed(notice: CompanionNotice) {
        close(notice.text)
    }

    private fun close(explanation: UiText) {
        selectedId = null
        connected = false
        info = null
        status = null
        statusRaw = null
        statusAtEpochMs = null
        live = null
        syncAvailable = false
        syncBusy = false
        syncProgress = null
        syncMessage = UiText.None
        lastSyncResult = null
        message = explanation
    }
}

internal data class LanSessionState(val connected: Boolean, val busy: Boolean, val message: UiText, val filesDone: Int, val filesTotal: Int, val bytesDone: Long, val bytesPerSecond: Double = 0.0, val bytesTotal: Long = 0)

internal data class LanRunState(
    val station: String?,
    val running: Boolean,
    val startedAtEpochMs: Long?,
    val finishedAtEpochMs: Long? = null,
    val result: SyncResult? = null,
    val interrupted: Boolean = false,
)
