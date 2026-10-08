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

/** The one waiting line, the same words as Android `now_waiting`. */
internal const val WAITING_FOR_READING = "Waiting for a reading…"

internal data class NearbyDevice(val id: String, val name: String, val rssi: Int)

/** Main-thread callbacks from the native BLE adapter. Never contains pairing or Wi-Fi secrets. */
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
    internal var message by mutableStateOf("")
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
    internal var lanMessage by mutableStateOf("")
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
    internal var wifiAccessMessage by mutableStateOf("")
        private set
    internal var lanSessions by mutableStateOf<Map<String, LanSessionState>>(emptyMap())
        private set
    internal var lanPaused by mutableStateOf<Set<String>>(emptySet())
        private set
    internal var exportBusy by mutableStateOf(false)
        private set
    internal var exportMessage by mutableStateOf("")
        private set

    fun exportChanged(busy: Boolean, explanation: String) {
        exportBusy = busy
        exportMessage = explanation
    }

    fun lanCollectionPaused(name: String, paused: Boolean) {
        lanPaused = if (paused) lanPaused + name else lanPaused - name
    }

    fun wifiAccessChanged(available: Boolean, busy: Boolean, explanation: String) {
        wifiAccessAvailable = available
        wifiAccessBusy = busy
        wifiAccessMessage = explanation
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
        lanMessage = "Looking on your Wi-Fi…"
    }

    fun lanDevicesFound(names: List<String>) {
        if (!lanScanning) return
        lanDevices = names.distinct().sorted()
        lanMessage = if (lanDevices.isEmpty()) "Looking on your Wi-Fi…" else "Sensors on your Wi-Fi"
    }

    fun lanScanStopped() {
        if (!lanScanning) return
        lanScanning = false
        if (lanDevices.isEmpty()) lanMessage = "No Wi-Fi sensors found. Check Local Network in Settings."
    }

    fun lanScanWaiting() {
        if (!lanScanning) return
        lanAccessDenied = false
        lanMessage = "Waiting for Wi-Fi or Local Network access."
    }

    /** The browse keeps running, so turning access on in Settings resumes discovery without a restart. */
    fun lanScanAccessDenied() {
        if (!lanScanning) return
        lanAccessDenied = true
        lanMessage = "Turn on Local Network for opensensor.space in Settings."
    }

    fun lanScanReady() {
        lanAccessDenied = false
        if (lanScanning && lanDevices.isEmpty()) lanMessage = "Looking on your Wi-Fi…"
    }

    fun lanScanUnavailable() {
        lanScanning = false
        lanDevices = emptyList()
        lanMessage = "Wi-Fi search is not available. Check Wi-Fi and Local Network in Settings."
    }

    /** The raw `CBManager.authorization` value, read when the central manager reports its state. */
    fun bluetoothAuthorizationChanged(raw: Long) {
        bluetoothAccess = IosAuthorization.bluetooth(raw)
    }

    fun bluetoothChanged(available: Boolean, explanation: String) {
        bluetoothAvailable = available
        message = explanation
        if (!available) {
            scanning = false
            connectionClosed(explanation)
        }
    }

    fun scanStarted() {
        devices = emptyList()
        scanning = true
        message = "Looking for nearby sensors…"
    }

    fun scanStopped() {
        scanning = false
        if (selectedId == null) message = if (devices.isEmpty()) "No sensors found. Keep your sensor nearby and try again." else "Choose a sensor to see its readings."
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
        message = "Connecting… If asked, enter the pairing PIN shown by your sensor."
    }

    /** Returns false for malformed or unsupported INFO; the adapter must close that connection. */
    fun receiveInfo(json: String): Boolean {
        val decoded = runCatching { DeviceInfo.parse(json) }.getOrNull()
        if (decoded == null || decoded.proto !in 1..AqGatt.PROTOCOL_VERSION || decoded.dev.isNullOrBlank()) {
            connectionClosed("This sensor has invalid or unsupported device information.")
            return false
        }
        info = decoded
        connected = true
        message = "Connected · live readings"
        return true
    }

    fun receiveStatus(json: String) {
        if (!connected) return
        status = runCatching { DeviceStatus.parse(json) }.getOrNull()
        if (status == null) message = "The sensor sent an unreadable status."
    }

    fun receiveLive(json: String) {
        if (!connected) return
        // A bad notification must not leave an earlier number looking current.
        live = runCatching { LiveSample.parse(json) }.getOrNull()
        message = if (live == null) "The sensor sent an unreadable reading." else "Connected · live readings"
    }

    fun readingExpired() {
        live = null
        if (connected) message = WAITING_FOR_READING
    }

    fun connectionClosed(explanation: String) {
        selectedId = null
        connected = false
        info = null
        status = null
        live = null
        message = explanation
    }
}

internal data class LanSessionState(val connected: Boolean, val busy: Boolean, val message: String, val filesDone: Int, val filesTotal: Int, val bytesDone: Long, val bytesPerSecond: Double = 0.0)
