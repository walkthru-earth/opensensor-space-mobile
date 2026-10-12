package earth.walkthru.opensensor

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import earth.walkthru.aqsync.settings.AppPrefs
import earth.walkthru.opensensor.discover.MapCache
import kotlinx.coroutines.runBlocking
import platform.UIKit.UIViewController

/**
 * The one screen the Swift app shows. It opens on the Discover map. My devices reads [devices].
 * [requestBluetooth] creates the central manager, which shows the system prompt the first time.
 * [openSensor] connects to a peripheral id, closing another open Bluetooth link first. A user open
 * cancels Collect all before it.
 * [syncBluetooth] syncs the connected Bluetooth sensor, and the app routes it to an open Wi-Fi
 * session for the same sensor. [linkFor] answers the open connection's [IosLink] to the sensor with
 * this hardware id, Wi-Fi first, or null when it is not connected. Debug, Device settings and Files
 * take leases of it. [command] sends a confirmed `SET_TIME` or `FLUSH` from Today over the
 * Bluetooth connection.
 * [autoSync] holds the per device Auto-sync switches, and [syncInBackground] hands one sensor to
 * the background run, answering true only when iOS took it as a continued task. [closeIdle] runs
 * when the last Today or Device page for a sensor leaves the back stack. Swift closes that
 * peripheral's link only when it is idle, never during a sync, a command, Collect all or a lease.
 * [forgetWifiAccess] drops the Keychain token and any
 * Wi-Fi session of a hardware id after the user rotated the token.
 * [scanSticker] presents the VisionKit sticker scanner and answers the decoded string, or null on a
 * cancel ([ScanSticker]). Swift passes null where `DataScannerViewController.isSupported` is false,
 * which hides Scan on the sticker card. Camera access is asked in Kotlin from the Scan tap first.
 */
fun mainViewController(
    state: CompanionState,
    devices: DevicesBridge,
    autoSync: AutoSyncSettings,
    requestBluetooth: () -> Unit,
    openSensor: (String) -> Unit,
    openBluetoothSensor: (String) -> Unit,
    openLanOnly: (String) -> Unit,
    disconnectSensor: (String) -> Unit,
    disconnect: () -> Unit,
    scanLan: () -> Unit,
    saveWifiAccess: () -> Unit,
    connectLan: (String) -> Unit,
    syncLan: (String) -> Unit,
    disconnectLan: (String) -> Unit,
    syncBluetooth: (String) -> Unit,
    syncInBackground: (String) -> Boolean,
    closeIdle: (String) -> Unit,
    linkFor: (String) -> IosLink?,
    command: (String, SensorCommand) -> Unit,
    syncOneFile: (String, String) -> Unit,
    cancelSync: (String) -> Unit,
    forgetWifiAccess: (String) -> Unit,
    scanSticker: ScanSticker?,
): UIViewController {
    // The basemap tile cache stays in Library/Caches under its stored cap, set before any map runs
    // (#61). One small settings read, blocking because the map runtime takes the cap only once. A
    // settings file that cannot be read at launch falls back to the default cap instead of a crash.
    val mapCacheBytes = try {
        runBlocking { settingsRepository.current().retention.mapCacheBytes }
    } catch (e: Exception) {
        println("settings: launch read failed, ${e::class.simpleName}")
        AppPrefs().retention.mapCacheBytes
    }
    MapCache.configure(cachesDirectory(), mapCacheBytes)
    IosMemoryPressure.install()
    // Every user path that opens, connects or syncs a sensor stops Collect all first, the same rule
    // for opening, Wi-Fi only, Smart Sync and Files. Restart reopen and cancel are not user takes.
    val userOpen = devices.userFirst(openSensor)
    val userOpenBluetooth = devices.userFirst(openBluetoothSensor)
    val userOpenLanOnly = devices.userFirst(openLanOnly)
    val userConnectLan = devices.userFirst(connectLan)
    val userSyncLan = devices.userFirst(syncLan)
    val userSyncBluetooth = devices.userFirst(syncBluetooth)
    val userSyncOneFile = devices.userFirst(syncOneFile)
    return ComposeUIViewController {
        val today = remember { CompanionToday(state, CompanionLinkActions(syncFiles = userSyncBluetooth, command = command, connect = userOpen, disconnect = disconnectSensor)) }
        val configLinks = remember {
            DeviceConfigLinks(
                openConfigLink = linkFor,
                saveWifiAccess = saveWifiAccess,
                forgetWifiAccess = forgetWifiAccess,
                connect = userOpen,
                connectBluetooth = userOpenBluetooth,
                reopen = openSensor,
                disconnect = disconnect,
                scanLan = scanLan,
                scanSticker = scanSticker,
            )
        }
        val filesActions = remember {
            DeviceFilesActions(linkFor, userSyncBluetooth, userSyncOneFile, cancelSync)
        }
        AppRoot(devices, today, requestBluetooth, userOpen, userOpenLanOnly, linkFor, closeIdle, configLinks, filesActions) { id, units, level, openDebug, openSettings, onBack ->
            val peripheral = IosKnown.isPeripheral(id)
            val records by devices.known.collectAsState()
            val known = peripheral && records.any { it.peripheral.equals(id, ignoreCase = true) }
            val selectedBluetooth = state.selectedId?.equals(id, ignoreCase = true) == true
            val lanName = state.connectedLanForStation(devices.stationFor(id))
            val selectedStation = devices.stationFor(id)
                ?: id.takeIf { it.startsWith(IosKnown.WIFI_PREFIX) }?.removePrefix(IosKnown.WIFI_PREFIX)
                    ?.let { name -> state.lanInfo[name]?.station ?: state.lanStations[name] }
                ?: state.info?.station?.takeIf { selectedBluetooth }
            val activeDevice = lanName?.let { state.lanInfo[it]?.dev }
                ?: state.info?.dev?.takeIf { selectedBluetooth && state.connected }
            // Detailed and Developer reveal the technical lines (signal, firmware, byte counters), as on Android.
            CompanionScreen(
                state = state,
                title = devices.nameFor(id),
                onBack = onBack,
                reconnect = if (peripheral) ({ userOpen(id) }) else null,
                disconnect = disconnect,
                scanLan = scanLan,
                saveWifiAccess = saveWifiAccess,
                connectLan = userConnectLan,
                syncLan = userSyncLan,
                disconnectLan = disconnectLan,
                syncBluetooth = { userSyncBluetooth(id) },
                selectedBluetooth = selectedBluetooth,
                selectedStation = selectedStation,
                activeDevice = activeDevice,
                units = units,
                showTechnical = level.detailed,
                onOpenDebug = openDebug,
                onOpenSettings = openSettings,
                autoSync = if (known) ({ AutoSyncSwitch(autoSync, id, syncInBackground) }) else null,
            )
        }
    }
}
