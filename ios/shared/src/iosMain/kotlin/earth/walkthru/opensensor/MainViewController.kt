package earth.walkthru.opensensor

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/**
 * The one screen the Swift app shows. It opens on the Discover map. My devices reads [devices].
 * [requestBluetooth] creates the central manager, which shows the system prompt the first time.
 * [openSensor] connects to a peripheral id, closing another open Bluetooth link first.
 * [syncBluetooth] syncs the connected Bluetooth sensor, and the app routes it to an open Wi-Fi
 * session for the same sensor.
 */
fun mainViewController(state: CompanionState, devices: DevicesBridge, requestBluetooth: () -> Unit, openSensor: (String) -> Unit, disconnect: () -> Unit, scanLan: () -> Unit, saveWifiAccess: () -> Unit, connectLan: (String) -> Unit, syncLan: (String) -> Unit, disconnectLan: (String) -> Unit, exportArchive: () -> Unit, syncBluetooth: () -> Unit): UIViewController =
    ComposeUIViewController {
        AppRoot(devices, requestBluetooth, openSensor) { id, units, level, onBack ->
            // Detailed and Developer reveal the technical lines (signal, firmware, byte counters), as on Android.
            CompanionScreen(
                state = state,
                title = devices.nameFor(id),
                onBack = onBack,
                reconnect = if (IosKnown.isPeripheral(id)) ({ openSensor(id) }) else null,
                disconnect = disconnect,
                scanLan = scanLan,
                saveWifiAccess = saveWifiAccess,
                connectLan = connectLan,
                syncLan = syncLan,
                disconnectLan = disconnectLan,
                exportArchive = exportArchive,
                syncBluetooth = syncBluetooth,
                units = units,
                showTechnical = level.detailed,
            )
        }
    }
