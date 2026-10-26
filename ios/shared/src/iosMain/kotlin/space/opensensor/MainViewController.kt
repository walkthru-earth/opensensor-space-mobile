package space.opensensor

import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.coroutines.runBlocking
import platform.UIKit.UIViewController
import space.opensensor.discover.MapCache
import space.opensensor.settings.AppPrefs

/**
 * The one screen the Swift app shows. It opens on the Discover map. My devices reads [devices].
 * [requestBluetooth] creates the central manager, which shows the system prompt the first time.
 * [openSensor] connects to a peripheral id, closing another open Bluetooth link first. A user open
 * cancels Collect all before it.
 * [syncBluetooth] syncs the connected Bluetooth sensor, and the app routes it to an open Wi-Fi
 * session for the same sensor. [linkFor] answers the open connection's [IosLink] to the sensor with
 * this hardware id, Wi-Fi first, or null when it is not connected. Debug, Device settings and Files
 * take leases of it. [bluetoothLinkFor] answers only the Bluetooth link, which a passphrase or PIN
 * change in Device settings borrows so it never travels over Wi-Fi. [command] sends a confirmed `SET_TIME` or `FLUSH` from Today over the
 * Bluetooth connection.
 * [autoSync] holds the per device Auto-sync switches, shown first on the Device section. [closeIdle] runs
 * when the last Today or Device page for a sensor leaves the back stack. Swift closes that
 * peripheral's link only when it is idle, never during a sync, a command, Collect all or a lease.
 * [forgetWifiAccess] drops the Keychain token and any
 * Wi-Fi session of a hardware id after the user rotated the token.
 * [scanSticker] presents the VisionKit sticker scanner and answers the decoded string, or null on a
 * cancel ([ScanSticker]). Swift passes null where `DataScannerViewController.isSupported` is false,
 * which hides Scan on the sticker card. Camera access is asked in Kotlin from the Scan tap first.
 */
fun mainViewController(
    state: CompanionState,
    devices: DevicesBridge,
    autoSync: AutoSyncSettings,
    requestBluetooth: () -> Unit,
    openSensor: (String) -> Unit,
    openBluetoothSensor: (String) -> Unit,
    openLanOnly: (String) -> Unit,
    disconnectSensor: (String) -> Unit,
    disconnect: () -> Unit,
    scanLan: () -> Unit,
    saveWifiAccess: () -> Unit,
    syncBluetooth: (String) -> Unit,
    closeIdle: (String) -> Unit,
    linkFor: (String) -> IosLink?,
    bluetoothLinkFor: (String) -> IosLink?,
    command: (String, SensorCommand) -> Unit,
    syncOneFile: (String, String) -> Unit,
    cancelSync: (String) -> Unit,
    forgetWifiAccess: (String) -> Unit,
    scanSticker: ScanSticker?,
): UIViewController {
    // The basemap tile cache stays in Library/Caches under its stored cap, set before any map runs
    // (#61). One small settings read, blocking because the map runtime takes the cap only once. A
    // settings file that cannot be read at launch falls back to the default cap instead of a crash.
    val mapCacheBytes = try {
        runBlocking { settingsRepository.current().retention.mapCacheBytes }
    } catch (e: Exception) {
        println("settings: launch read failed, ${e::class.simpleName}")
        AppPrefs().retention.mapCacheBytes
    }
    MapCache.configure(cachesDirectory(), mapCacheBytes)
    IosMemoryPressure.install()
    // A user path that opens, connects or syncs a sensor the Collect all pass holds or plans stops
    // the pass first, the same rule for opening, Wi-Fi only, Smart Sync and Files. An open that may
    // take Bluetooth also stops a pass on Bluetooth. Restart reopen and cancel are not user takes.
    val userOpen = devices.userFirst(openSensor, takesBluetooth = true)
    val userOpenBluetooth = devices.userFirst(openBluetoothSensor, takesBluetooth = true)
    val userOpenLanOnly = devices.userFirst(openLanOnly)
    val userSyncBluetooth = devices.userFirst(syncBluetooth)
    val userSyncOneFile = devices.userFirst(syncOneFile)
    return ComposeUIViewController {
        val today = remember { CompanionToday(state, CompanionLinkActions(syncFiles = userSyncBluetooth, command = command, connect = userOpen, disconnect = disconnectSensor, cancelSync = cancelSync)) }
        val configLinks = remember {
            DeviceConfigLinks(
                openConfigLink = linkFor,
                openBluetoothLink = bluetoothLinkFor,
                saveWifiAccess = saveWifiAccess,
                forgetWifiAccess = forgetWifiAccess,
                connect = userOpen,
                connectBluetooth = userOpenBluetooth,
                reopen = openSensor,
                disconnect = disconnect,
                scanLan = scanLan,
                scanSticker = scanSticker,
            )
        }
        val filesActions = remember {
            DeviceFilesActions(linkFor, userSyncBluetooth, userSyncOneFile, cancelSync)
        }
        AppRoot(devices, today, requestBluetooth, userOpen, userOpenLanOnly, linkFor, closeIdle, configLinks, filesActions, autoSync)
    }
}
