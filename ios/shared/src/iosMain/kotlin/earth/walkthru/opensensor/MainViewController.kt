package earth.walkthru.opensensor

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.window.ComposeUIViewController
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import platform.UIKit.UIViewController

/**
 * The one screen the Swift app shows. It opens on the Discover map. My devices reads [devices].
 * [requestBluetooth] creates the central manager, which shows the system prompt the first time.
 * [openSensor] connects to a peripheral id, closing another open Bluetooth link first. A user open
 * cancels Collect all before it.
 * [syncBluetooth] syncs the connected Bluetooth sensor, and the app routes it to an open Wi-Fi
 * session for the same sensor. [openDebugLink] lends the Debug screen a fresh link to the sensor
 * with this hardware id, or returns null while a sync or another request holds it. Closing the
 * link hands it back.
 * [autoSync] holds the per device Auto-sync switches, and [syncInBackground] hands one sensor to
 * the background run, answering true only when iOS took it as a continued task. [closeIdle] runs when a device page leaves the back stack. Swift closes that
 * peripheral's link only when it is idle, never during a sync or a Debug lend.
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
): UIViewController =
    ComposeUIViewController {
        // A sensor the user opens takes the one Bluetooth link, so Collect all stops first.
        val userOpen: (String) -> Unit = { id ->
            devices.cancelCollectAll()
            openSensor(id)
        }
        AppRoot(devices, requestBluetooth, userOpen, openDebugLink) { id, units, level, openDebug, onBack ->
            val peripheral = IosKnown.isPeripheral(id)
            if (peripheral) {
                // Cleared with this page's navigation entry, so popping the page offers its link back.
                viewModel(key = "page-lease-$id") { PageLease { closeIdle(id) } }
            }
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
                autoSync = if (known) ({ AutoSyncSwitch(autoSync, id, syncInBackground) }) else null,
            )
        }
    }

/** Runs [cleared] once when the device page's navigation entry is removed. */
private class PageLease(private val cleared: () -> Unit) : ViewModel() {
    override fun onCleared() = cleared()
}
