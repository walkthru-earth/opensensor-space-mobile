package earth.walkthru.opensensor

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/** The one screen the Swift app shows. It opens on the Discover map. */
fun mainViewController(state: CompanionState, scan: () -> Unit, connect: (String) -> Unit, disconnect: () -> Unit, scanLan: () -> Unit, saveWifiAccess: () -> Unit, connectLan: (String) -> Unit, syncLan: (String) -> Unit, disconnectLan: (String) -> Unit, exportArchive: () -> Unit): UIViewController =
    ComposeUIViewController {
        AppRoot { units ->
            CompanionScreen(state, scan, connect, disconnect, scanLan, saveWifiAccess, connectLan, syncLan, disconnectLan, exportArchive, units)
        }
    }
