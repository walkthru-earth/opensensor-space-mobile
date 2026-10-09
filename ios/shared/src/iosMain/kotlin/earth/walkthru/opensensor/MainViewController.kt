package earth.walkthru.opensensor

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
 * link hands it back.
 */
fun mainViewController(state: CompanionState, devices: DevicesBridge, requestBluetooth: () -> Unit, openSensor: (String) -> Unit, disconnect: () -> Unit, scanLan: () -> Unit, saveWifiAccess: () -> Unit, connectLan: (String) -> Unit, syncLan: (String) -> Unit, disconnectLan: (String) -> Unit, syncBluetooth: () -> Unit, openDebugLink: (String) -> SensorLink?): UIViewController =
    ComposeUIViewController {
        // A sensor the user opens takes the one Bluetooth link, so Collect all stops first.
        val userOpen: (String) -> Unit = { id ->
            devices.cancelCollectAll()
            openSensor(id)
        }
        AppRoot(devices, requestBluetooth, userOpen, openDebugLink) { id, units, level, openDebug, onBack ->
            // Detailed and Developer reveal the technical lines (signal, firmware, byte counters), as on Android.
            CompanionScreen(
                state = state,
                title = devices.nameFor(id),
                onBack = onBack,
                reconnect = if (IosKnown.isPeripheral(id)) ({ userOpen(id) }) else null,
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
            )
        }
    }
