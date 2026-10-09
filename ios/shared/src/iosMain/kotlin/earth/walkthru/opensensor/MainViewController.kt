package earth.walkthru.opensensor

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/**
 * The one screen the Swift app shows. It opens on the Discover map. [syncBluetooth] syncs the
 * connected Bluetooth sensor, and the app routes it to an open Wi-Fi session for the same sensor.
 */
fun mainViewController(state: CompanionState, scan: () -> Unit, connect: (String) -> Unit, disconnect: () -> Unit, scanLan: () -> Unit, saveWifiAccess: () -> Unit, connectLan: (String) -> Unit, syncLan: (String) -> Unit, disconnectLan: (String) -> Unit, syncBluetooth: () -> Unit): UIViewController =
    ComposeUIViewController {
        // Detailed and Developer reveal the technical lines (signal, firmware, byte counters), as on Android.
        AppRoot { units, level ->
            CompanionScreen(state, scan, connect, disconnect, scanLan, saveWifiAccess, connectLan, syncLan, disconnectLan, syncBluetooth, units, showTechnical = level.detailed)
        }
    }
