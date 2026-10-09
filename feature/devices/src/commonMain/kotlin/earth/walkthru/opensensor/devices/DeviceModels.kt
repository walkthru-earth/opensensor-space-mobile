package earth.walkthru.opensensor.devices

import androidx.compose.runtime.Immutable

/**
 * A Bluetooth advertisement seen in the current scan. [id] is the platform's peripheral id (the
 * Android MAC address, the iOS CBPeripheral identifier). A nearby sensor is only a scan result, it
 * becomes known once the owner connects to it.
 */
@Immutable
data class NearbySensor(val id: String, val name: String?, val rssi: Int, val bonded: Boolean = false)

/**
 * A sensor this phone connected to before. [station] is the station id from its INFO, null until it
 * was read. [hasWifiAccess] says the phone holds its LAN token (the token itself never leaves the
 * platform store). [lastSeenMs] is null when the platform does not record it.
 */
@Immutable
data class KnownSensor(
    val id: String,
    val name: String?,
    val station: String?,
    val lastSeenMs: Long?,
    val hasWifiAccess: Boolean,
)

/** A sensor found on the local Wi-Fi through `_aqsync._tcp`. [station] comes from its TXT record. */
@Immutable
data class WifiSensor(val name: String, val station: String?)

/** Where an open link stands, in the words the list shows. */
enum class LinkPhase { CONNECTING, PAIRING, CONNECTED, RECONNECTING, FAILED }

/**
 * An open or opening link, or a background sync that owns the sensor. [collecting] with the file
 * counts while a sync runs, [background] while a background worker owns it.
 */
@Immutable
data class ActiveLink(
    val id: String,
    val name: String?,
    val phase: LinkPhase,
    val collecting: Boolean = false,
    val filesDone: Int = 0,
    val filesTotal: Int = 0,
    val background: Boolean = false,
)

/**
 * What the archive on this phone says about one station, never a live link. The last UTC day with
 * valid readings, its PM2.5 mean and coverage when the platform computes them.
 */
@Immutable
data class ArchiveGlance(
    val files: Int,
    val lastSyncedMs: Long?,
    val lastDataUtcNs: Long?,
    val lastDayStartS: Long? = null,
    val lastDayPm25Mean: Float? = null,
    val lastDayCoverage: Float? = null,
)

/** Why the last scan stopped early. */
enum class ScanProblem { BLUETOOTH_OFF, LOCAL_NETWORK_DENIED, FAILED }

/** The Wi-Fi tag on a known row. */
enum class WifiTag { WITH_ACCESS, NO_ACCESS }

/** One row of the list, nearby or known or both. */
@Immutable
data class DeviceRow(
    val id: String,
    val name: String?,
    val station: String?,
    /** Signal strength while it advertises nearby, null when only known. */
    val rssi: Int?,
    val bonded: Boolean,
    val known: Boolean,
    val wifi: WifiTag?,
    val lastSeenMs: Long?,
    val archive: ArchiveGlance?,
) {
    val nearby: Boolean get() = rssi != null
}

/**
 * The merged list. [active] links first, then [nearby] sensors by signal, then [known] sensors that
 * do not advertise now, then [wifiOnly] sensors on the Wi-Fi that this phone does not know.
 */
@Immutable
data class DeviceList(
    val active: List<ActiveLink> = emptyList(),
    val nearby: List<DeviceRow> = emptyList(),
    val known: List<DeviceRow> = emptyList(),
    val wifiOnly: List<WifiSensor> = emptyList(),
    /** How many known sensors Collect all covers. */
    val knownCount: Int = 0,
) {
    val isEmpty: Boolean get() = active.isEmpty() && nearby.isEmpty() && known.isEmpty() && wifiOnly.isEmpty()
}

/** The sensor a Forget confirmation is open for. */
@Immutable
data class ForgetTarget(val id: String, val name: String?)

/** Everything the screen draws. */
@Immutable
data class DevicesUiState(
    val list: DeviceList = DeviceList(),
    val scanning: Boolean = false,
    val scanProblem: ScanProblem? = null,
    val collecting: Boolean = false,
    val forget: ForgetTarget? = null,
) {
    /** Collect all shows from two known sensors, one sensor syncs from its own row. */
    val showCollectAll: Boolean get() = list.knownCount >= 2
}

/** What the user asked for. The platform route turns these into port calls. */
sealed interface DevicesAction {
    data object StartScan : DevicesAction
    data object StopScan : DevicesAction

    /** Open the sensor. [connect] is false when Bluetooth was declined, so it opens on its archive. */
    data class Open(val id: String, val name: String?, val connect: Boolean) : DevicesAction
    data class RequestForget(val id: String, val name: String?) : DevicesAction
    data object ConfirmForget : DevicesAction
    data object CancelForget : DevicesAction
    data object CollectAll : DevicesAction
}
