package earth.walkthru.opensensor

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/**
 * The one screen the Swift app shows. [startOnDiscover] is true when no device is paired yet, so
 * the Discover map is the start tab.
 */
fun mainViewController(state: CompanionState, startOnDiscover: Boolean, scan: () -> Unit, connect: (String) -> Unit, disconnect: () -> Unit, scanLan: () -> Unit, saveWifiAccess: () -> Unit, connectLan: (String) -> Unit, syncLan: (String) -> Unit, disconnectLan: (String) -> Unit, exportArchive: () -> Unit): UIViewController =
    ComposeUIViewController {
        AppRoot(startOnDiscover) {
            CompanionScreen(state, scan, connect, disconnect, scanLan, saveWifiAccess, connectLan, syncLan, disconnectLan, exportArchive)
        }
    }
