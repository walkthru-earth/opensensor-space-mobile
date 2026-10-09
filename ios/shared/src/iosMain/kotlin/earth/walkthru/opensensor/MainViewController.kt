package earth.walkthru.opensensor

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/**
 * The one screen the Swift app shows. It opens on the Discover map. [syncBluetooth] syncs the
 * connected Bluetooth sensor, and the app routes it to an open Wi-Fi session for the same sensor.
 * [openDebugLink] lends the Debug screen a fresh link to the sensor with this hardware id, or
 * returns null while a sync or another request holds it. Closing the link hands it back.
 */
fun mainViewController(state: CompanionState, scan: () -> Unit, connect: (String) -> Unit, disconnect: () -> Unit, scanLan: () -> Unit, saveWifiAccess: () -> Unit, connectLan: (String) -> Unit, syncLan: (String) -> Unit, disconnectLan: (String) -> Unit, syncBluetooth: () -> Unit, openDebugLink: (String) -> SensorLink?): UIViewController =
    ComposeUIViewController {
        // Detailed and Developer reveal the technical lines (signal, firmware, byte counters), as on Android.
        AppRoot(state, openDebugLink) { units, level, openDebug ->
            CompanionScreen(state, scan, connect, disconnect, scanLan, saveWifiAccess, connectLan, syncLan, disconnectLan, syncBluetooth, units, showTechnical = level.detailed, onOpenDebug = openDebug)
        }
    }
