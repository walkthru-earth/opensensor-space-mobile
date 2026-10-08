package earth.walkthru.opensensor

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

fun mainViewController(state: CompanionState, scan: () -> Unit, connect: (String) -> Unit, disconnect: () -> Unit, scanLan: () -> Unit, saveWifiAccess: () -> Unit, connectLan: (String) -> Unit, syncLan: (String) -> Unit, disconnectLan: (String) -> Unit, exportArchive: () -> Unit): UIViewController =
    ComposeUIViewController { CompanionScreen(state, scan, connect, disconnect, scanLan, saveWifiAccess, connectLan, syncLan, disconnectLan, exportArchive) }
