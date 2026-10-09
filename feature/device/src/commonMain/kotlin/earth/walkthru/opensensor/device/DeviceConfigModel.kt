package earth.walkthru.opensensor.device

import earth.walkthru.aqsync.data.WifiScanState
import earth.walkthru.aqsync.data.ble.ConfigSnapshot
import earth.walkthru.aqsync.data.ble.ConnectionState
import earth.walkthru.aqsync.data.ble.TransportKind
import earth.walkthru.aqsync.data.history.DeviceCapabilities
import earth.walkthru.aqsync.data.protocol.ConfigPatch
import earth.walkthru.aqsync.data.protocol.ConfigProblem
import earth.walkthru.aqsync.data.protocol.DeviceInfo
import earth.walkthru.aqsync.data.protocol.PairMode
import earth.walkthru.aqsync.settings.DetailLevel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** The sensor module this phone recorded for the device, for the sticker card. Android only today. */
data class SensorRecord(val serial: String?, val count: Int)

/**
 * Everything the Device section shows about one sensor, as the platform reports it. [config] is the
 * last `GET_CONFIG` or `SET_CONFIG` answer, whose `toString` leaves out the raw JSON and the passkey.
 * Missing values stay null, the screen never invents one.
 */
data class DeviceConfigSnapshot(
    val address: String? = null,
    val name: String? = null,
    val connection: ConnectionState = ConnectionState.Disconnected,
    val transport: TransportKind? = null,
    val info: DeviceInfo? = null,
    val config: ConfigSnapshot? = null,
    val wifiScan: WifiScanState = WifiScanState.Idle,
    /** The sensor is seen on this Wi-Fi network. [lanEndpoint] is its `host:port` when known. */
    val lanSeen: Boolean = false,
    val lanEndpoint: String? = null,
    val lanDiscoveryError: String? = null,
    /** This phone holds a LAN token for the sensor. Never the token itself. */
    val hasLanToken: Boolean = false,
    val busy: Boolean = false,
    /** A file transfer is running on this link. */
    val syncRunning: Boolean = false,
    val sensorRecord: SensorRecord? = null,
)

/** The state the Device section draws: the snapshot, the detail level, and what follows from both. */
data class DeviceConfigUiState(
    val snapshot: DeviceConfigSnapshot = DeviceConfigSnapshot(),
    val level: DetailLevel = DetailLevel.SIMPLE,
) {
    /** What this device can do, from `INFO` (shared mapping, no schema strings in the UI). */
    val capabilities: DeviceCapabilities get() = DeviceCapabilities.from(snapshot.info)
    val isV2: Boolean get() = capabilities.configurable
    val ready: Boolean get() = snapshot.connection.isReady
    val canEdit: Boolean get() = ready && isV2 && !snapshot.busy

    /**
     * Actions that drop the link (reboot, rotate token, forget bonds) are also refused while a
     * transfer runs. A `REBOOT` mid-sync would kill the download and finalize the RAM batch under it.
     */
    val canDisrupt: Boolean get() = canEdit && !snapshot.syncRunning
    val overBle: Boolean get() = snapshot.transport == TransportKind.BLE || snapshot.transport == TransportKind.FAKE

    /** Simple still shows the pairing card for a default PIN in fixed mode or a pending restart. */
    val pairingNeedsAttention: Boolean get() {
        val ble = snapshot.config?.config?.ble
        return (ble?.isPinDefault == true && ble.pairMode == PairMode.FIXED) || snapshot.config?.rebootRequired == true
    }
}

/** Which settings a `SET_CONFIG` changed, for the confirmation line. */
enum class DeviceConfigChange { PAIRING, WIFI_SENT, WIFI_ON, WIFI_OFF, LAN_ON, LAN_OFF }

/** Device requests a notice can name. */
enum class DeviceConfigCommand { GET_CONFIG, SET_CONFIG, REBOOT, GET_TOKEN, ROTATE_TOKEN }

/** One-off outcomes for the snackbar. None carries a passkey, passphrase or token. */
sealed interface DeviceConfigNotice {
    data class Problem(val problem: ConfigProblem) : DeviceConfigNotice
    data object StickerInvalid : DeviceConfigNotice
    data object SensorBusy : DeviceConfigNotice
    data class SensorSaved(val batchEstimate: Boolean) : DeviceConfigNotice
    data object SensorNotConfirmed : DeviceConfigNotice
    data object SensorFailed : DeviceConfigNotice

    /** The link is doing something else, nothing was sent. */
    data object Busy : DeviceConfigNotice
    data class Saved(val change: DeviceConfigChange, val rebootRequired: Boolean) : DeviceConfigNotice

    /** `ERROR` 12. [key] is the protocol key the device named, shown verbatim. */
    data class KeyRejected(val key: String) : DeviceConfigNotice
    data class Rejected(val command: DeviceConfigCommand) : DeviceConfigNotice
    data class Failed(val command: DeviceConfigCommand) : DeviceConfigNotice
    data object Rebooting : DeviceConfigNotice
    data object TokenStored : DeviceConfigNotice

    /** The sensor took a new token. [stored] when this phone already holds it, else fetch it again later. */
    data class TokenRotated(val stored: Boolean) : DeviceConfigNotice
}

/**
 * A pairing mode and an optional new six digit passkey, as the user picked them. `toString` never
 * prints the passkey.
 */
class PairingChoice(val mode: PairMode, val pin: String?) {
    override fun toString(): String = "PairingChoice(mode=$mode, pin=${if (pin.isNullOrEmpty()) "none" else "<redacted>"})"
    override fun equals(other: Any?): Boolean = other is PairingChoice && other.mode == mode && other.pin == pin
    override fun hashCode(): Int = mode.hashCode() * 31 + (pin?.hashCode() ?: 0)
}

/** A network to join. `toString` never prints the passphrase. */
class WifiJoin(val ssid: String, val psk: String, val open: Boolean) {
    override fun toString(): String = "WifiJoin(ssid=$ssid, psk=<${if (open || psk.isEmpty()) "none" else "redacted"}>, open=$open)"
    override fun equals(other: Any?): Boolean = other is WifiJoin && other.ssid == ssid && other.psk == psk && other.open == open
    override fun hashCode(): Int = (ssid.hashCode() * 31 + psk.hashCode()) * 31 + open.hashCode()
}

/** The result of one sticker scan, delivered by the platform. [id] makes a repeated scan distinct. */
data class StickerScan(val value: String?, val error: String?, val id: Long)

/** What the LAN card needs from the platform's local network permission. */
data class LanAccess(val granted: Boolean, val requestable: Boolean)

/**
 * The port between the shared Device section and a platform's live link. Android adapts
 * `DeviceConnectionRepository`, iOS borrows the Bluetooth link from `BluetoothCompanion`. Transport
 * stays behind it. The controller decides what may be sent and when, the source only sends it.
 */
interface DeviceConfigSource {
    val snapshot: StateFlow<DeviceConfigSnapshot>

    /** Outcomes the platform reports itself. Android reports through its own repository events. */
    val notices: Flow<DeviceConfigNotice>

    /** `GET_CONFIG`. */
    fun refreshConfig()

    /** One validated `SET_CONFIG`. [patch] prints its keys only. */
    fun applyConfig(patch: ConfigPatch, change: DeviceConfigChange)

    /** `REBOOT`, then reconnect. Called only after the user confirmed it. */
    fun reboot()

    fun scanWifi()
    fun clearWifiScan()

    /** `GET_TOKEN` over Bluetooth, stored encrypted by the platform. */
    fun fetchToken()

    /** `lan.rotate_token`, then the new token for this phone. Called only after the user confirmed it. */
    fun rotateToken()

    /** `ble.clear_bonds`. Called only after the user confirmed it. */
    fun clearBonds()

    /** `SET_CONFIG` with the sticker. Returns the board's answer, throws when it did not take it. */
    suspend fun applySensorConfig(patch: ConfigPatch): ConfigSnapshot

    /** Keep this phone's record of the fitted module, after the board confirmed it. */
    suspend fun recordSensor(serial: String, vendor: String, model: String, batch: String, source: String)

    fun reconnect()
    fun reconnectOverBluetooth()
}
