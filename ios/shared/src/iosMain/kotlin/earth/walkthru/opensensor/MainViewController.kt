package earth.walkthru.opensensor

import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/**
 * The one screen the Swift app shows. It opens on the Discover map. [syncBluetooth] syncs the
 * connected Bluetooth sensor, and the app routes it to an open Wi-Fi session for the same sensor.
 * [command] sends a confirmed `SET_TIME` or `FLUSH` from Today over the Bluetooth connection.
 */
fun mainViewController(state: CompanionState, scan: () -> Unit, connect: (String) -> Unit, disconnect: () -> Unit, scanLan: () -> Unit, saveWifiAccess: () -> Unit, connectLan: (String) -> Unit, syncLan: (String) -> Unit, disconnectLan: (String) -> Unit, exportArchive: () -> Unit, syncBluetooth: () -> Unit, command: (SensorCommand) -> Unit): UIViewController =
    ComposeUIViewController {
        val today = remember { CompanionToday(state, CompanionLinkActions(syncFiles = syncBluetooth, command = command, connect = connect, disconnect = disconnect)) }
        // Detailed and Developer reveal the technical lines (signal, firmware, byte counters), as on Android.
        AppRoot(today) { units, level ->
            CompanionScreen(state, scan, connect, disconnect, scanLan, saveWifiAccess, connectLan, syncLan, disconnectLan, exportArchive, syncBluetooth, units, showTechnical = level.detailed)
        }
    }
