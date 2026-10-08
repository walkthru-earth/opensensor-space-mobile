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
import earth.walkthru.opensensor.strings.resources.companion_scan_choose
import earth.walkthru.opensensor.strings.resources.companion_scan_looking
import earth.walkthru.opensensor.strings.resources.companion_scan_none
import earth.walkthru.opensensor.strings.resources.companion_status_unreadable
import earth.walkthru.opensensor.strings.resources.now_waiting

internal data class NearbyDevice(val id: String, val name: String, val rssi: Int)

/**
 * Main-thread callbacks from the native BLE adapter. Never contains pairing or Wi-Fi secrets.
 * Messages are [UiText]. The state picks a `:ui:strings` key and the screen resolves the words.
 * Explanations that the Swift adapters still word themselves arrive as [UiText.Plain].
 */
class CompanionState {
    internal var devices by mutableStateOf<List<NearbyDevice>>(emptyList())
        private set
    internal var scanning by mutableStateOf(false)
        private set
    internal var bluetoothAvailable by mutableStateOf(false)
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
    internal var live by mutableStateOf<LiveSample?>(null)
        private set
    internal val reading: NowReading get() = NowReading.of(live)
    internal val profile get() = LiveProfiles.resolve(info)

    internal var lanDevices by mutableStateOf<List<String>>(emptyList())
        private set
    internal var lanScanning by mutableStateOf(false)
        private set
    internal var lanMessage by mutableStateOf<UiText>(UiText.None)
        private set

    /** True only after iOS reports the Local Network policy denial (TN3179), never for plain waiting. */
    internal var lanAccessDenied by mutableStateOf(false)
        private set

    internal var wifiAccessAvailable by mutableStateOf(false)
        private set
    internal var wifiAccessBusy by mutableStateOf(false)
        private set
    internal var wifiAccessMessage by mutableStateOf<UiText>(UiText.None)
        private set
    internal var lanSessions by mutableStateOf<Map<String, LanSessionState>>(emptyMap())
        private set
    internal var lanPaused by mutableStateOf<Set<String>>(emptySet())
        private set
    internal var exportBusy by mutableStateOf(false)
        private set
    internal var exportMessage by mutableStateOf<UiText>(UiText.None)
        private set

    fun exportChanged(busy: Boolean, explanation: String) {
        exportBusy = busy
        exportMessage = UiText.plain(explanation)
    }

    fun lanCollectionPaused(name: String, paused: Boolean) {
        lanPaused = if (paused) lanPaused + name else lanPaused - name
    }

    fun wifiAccessChanged(available: Boolean, busy: Boolean, explanation: String) {
        wifiAccessAvailable = available
        wifiAccessBusy = busy
        wifiAccessMessage = UiText.plain(explanation)
    }

    /** Native sessions are independent: a callback updates only its own sensor card. */
    fun lanSessionChanged(name: String, connected: Boolean, busy: Boolean, message: String, filesDone: Int, filesTotal: Int, bytesDone: Long, bytesPerSecond: Double = 0.0) {
        val rate = bytesPerSecond.takeIf { connected && busy && it.isFinite() && it > 0.0 } ?: 0.0
        lanSessions = lanSessions + (name to LanSessionState(connected, busy, message, filesDone, filesTotal, bytesDone, rate))
        if (!connected) lanReadings = lanReadings - name
    }

    internal var lanReadings by mutableStateOf<Map<String, NowReading>>(emptyMap())
        private set

    fun lanReadingReceived(name: String, json: String) {
        if (lanSessions[name]?.connected != true) return
        val sample = runCatching { LiveSample.parse(json) }.getOrNull()
        lanReadings = lanReadings + (name to NowReading.of(sample))
    }

    fun lanReadingExpired(name: String) {
        lanReadings = lanReadings - name
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
        lanScanning = true
        lanMessage = UiText.of(Res.string.companion_lan_looking)
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
        lanMessage = UiText.of(Res.string.companion_lan_unavailable)
    }

    fun bluetoothChanged(available: Boolean, explanation: String) {
        bluetoothAvailable = available
        message = UiText.plain(explanation)
        if (!available) {
            scanning = false
            connectionClosed(explanation)
        }
    }

    fun scanStarted() {
        devices = emptyList()
        scanning = true
        message = UiText.of(Res.string.companion_scan_looking)
    }

    fun scanStopped() {
        scanning = false
        if (selectedId == null) message = if (devices.isEmpty()) UiText.of(Res.string.companion_scan_none) else UiText.of(Res.string.companion_scan_choose)
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

    fun connectionClosed(explanation: String) {
        close(UiText.plain(explanation))
    }

    private fun close(explanation: UiText) {
        selectedId = null
        connected = false
        info = null
        status = null
        live = null
        message = explanation
    }
}

internal data class LanSessionState(val connected: Boolean, val busy: Boolean, val message: String, val filesDone: Int, val filesTotal: Int, val bytesDone: Long, val bytesPerSecond: Double = 0.0)
