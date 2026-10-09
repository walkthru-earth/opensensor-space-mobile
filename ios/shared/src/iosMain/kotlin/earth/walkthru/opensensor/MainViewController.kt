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
 */
fun mainViewController(
    state: CompanionState,
    devices: DevicesBridge,
    autoSync: AutoSyncSettings,
    requestBluetooth: () -> Unit,
    openSensor: (String) -> Unit,
    disconnect: () -> Unit,
    scanLan: () -> Unit,
    saveWifiAccess: () -> Unit,
    connectLan: (String) -> Unit,
    syncLan: (String) -> Unit,
    disconnectLan: (String) -> Unit,
    syncBluetooth: () -> Unit,
    syncInBackground: (String) -> Boolean,
    closeIdle: (String) -> Unit,
    openDebugLink: (String) -> SensorLink?,
    command: (SensorCommand) -> Unit,
    openConfigLink: (String) -> SensorLink?,
    forgetWifiAccess: (String) -> Unit,
): UIViewController =
    ComposeUIViewController {
        // A sensor the user opens takes the one Bluetooth link, so Collect all stops first.
        val userOpen: (String) -> Unit = { id ->
            devices.cancelCollectAll()
            openSensor(id)
        }
        val today = remember { CompanionToday(state, CompanionLinkActions(syncFiles = syncBluetooth, command = command, connect = userOpen, disconnect = disconnect)) }
        val configLinks = remember {
            DeviceConfigLinks(
                openConfigLink = openConfigLink,
                saveWifiAccess = saveWifiAccess,
                forgetWifiAccess = forgetWifiAccess,
                connect = userOpen,
                reopen = openSensor,
                disconnect = disconnect,
                scanLan = scanLan,
            )
        }
        AppRoot(devices, today, requestBluetooth, userOpen, openDebugLink, closeIdle, configLinks) { id, units, level, openDebug, openSettings, onBack ->
            val peripheral = IosKnown.isPeripheral(id)
            val records by devices.known.collectAsState()
            val known = peripheral && records.any { it.peripheral.equals(id, ignoreCase = true) }
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
                syncBluetooth = syncBluetooth,
                units = units,
                showTechnical = level.detailed,
                onOpenDebug = openDebug,
                onOpenSettings = openSettings,
                autoSync = if (known) ({ AutoSyncSwitch(autoSync, id, syncInBackground) }) else null,
            )
        }
    }
