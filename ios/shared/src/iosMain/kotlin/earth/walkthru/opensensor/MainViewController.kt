package earth.walkthru.opensensor

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/**
 * The one screen the Swift app shows. It opens on the Discover map. My devices reads [devices].
 * [requestBluetooth] creates the central manager, which shows the system prompt the first time.
 * [openSensor] connects to a peripheral id, closing another open Bluetooth link first. A user open
 * cancels Collect all before it.
 * [syncBluetooth] syncs the connected Bluetooth sensor, and the app routes it to an open Wi-Fi
 * session for the same sensor. [openDebugLink] lends the Debug screen a fresh link to the sensor
 * with this hardware id, or returns null while a sync or another request holds it. Closing the
 * link hands it back. [command] sends a confirmed `SET_TIME` or `FLUSH` from Today over the
 * Bluetooth connection.
 * [autoSync] holds the per device Auto-sync switches, and [syncInBackground] hands one sensor to
 * the background run, answering true only when iOS took it as a continued task. [closeIdle] runs
 * when the last Today or Device page for a sensor leaves the back stack. Swift closes that
 * peripheral's link only when it is idle, never during a sync, a command, Collect all or a Debug lend.
 * [openConfigLink] lends Device settings a link under the same guards as [command], and closing it
 * settles the link as a command's end does. [forgetWifiAccess] drops the Keychain token and any
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
    openDebugLink: (String) -> SensorLink?,
    command: (String, SensorCommand) -> Unit,
    openConfigLink: (String) -> SensorLink?,
    syncOneFile: (String, String) -> Unit,
    cancelSync: (String) -> Unit,
    forgetWifiAccess: (String) -> Unit,
    scanSticker: ScanSticker?,
): UIViewController =
    ComposeUIViewController {
        // A sensor the user opens may take Wi-Fi or the one Bluetooth link, so Collect all stops first.
        val userOpen: (String) -> Unit = { id ->
            devices.cancelCollectAll()
            openSensor(id)
        }
        val userOpenBluetooth: (String) -> Unit = { id ->
            devices.cancelCollectAll()
            openBluetoothSensor(id)
        }
        val today = remember { CompanionToday(state, CompanionLinkActions(syncFiles = syncBluetooth, command = command, connect = userOpen, disconnect = disconnectSensor)) }
        val configLinks = remember {
            DeviceConfigLinks(
                openConfigLink = openConfigLink,
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
            DeviceFilesActions(openConfigLink, syncBluetooth, syncOneFile, cancelSync)
        }
        AppRoot(devices, today, requestBluetooth, userOpen, openLanOnly, openDebugLink, closeIdle, configLinks, filesActions) { id, units, level, openDebug, openSettings, onBack ->
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
                connectLan = connectLan,
                syncLan = syncLan,
                disconnectLan = disconnectLan,
                syncBluetooth = { syncBluetooth(id) },
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
