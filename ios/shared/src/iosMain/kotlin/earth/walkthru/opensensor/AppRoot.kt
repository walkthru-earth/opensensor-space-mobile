package earth.walkthru.opensensor

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import earth.walkthru.aqsync.publicfeed.platformH3Grid
import earth.walkthru.aqsync.settings.DetailLevel
import earth.walkthru.aqsync.settings.SettingsRepository
import earth.walkthru.aqsync.settings.iosSettingsRepository
import earth.walkthru.aqsync.units.UnitSystem
import earth.walkthru.aqsync.units.systemUnitSystem
import earth.walkthru.opensensor.designsystem.OpenSensorTheme
import earth.walkthru.opensensor.devices.DevicesAction
import earth.walkthru.opensensor.devices.DevicesPlatform
import earth.walkthru.opensensor.devices.MyDevicesScreen
import earth.walkthru.opensensor.devices.bluetoothAccessTexts
import earth.walkthru.opensensor.discover.DiscoverController
import earth.walkthru.opensensor.discover.DiscoverScreen
import earth.walkthru.opensensor.discover.UserDefaultsDiscoverPrefs
import earth.walkthru.opensensor.discover.bundledSampleFeed
import earth.walkthru.opensensor.permissions.AccessDialogs
import earth.walkthru.opensensor.permissions.AccessHooks
import earth.walkthru.opensensor.permissions.AccessKind
import earth.walkthru.opensensor.permissions.AccessStatus
import earth.walkthru.opensensor.permissions.RuntimeAccess
import earth.walkthru.opensensor.permissions.rememberOpenAppSettings
import earth.walkthru.opensensor.settings.SettingsController
import earth.walkthru.opensensor.settings.SettingsScreen
import earth.walkthru.opensensor.shell.AppShell
import earth.walkthru.opensensor.shell.DiscoverKey
import earth.walkthru.opensensor.shell.ScanKey
import earth.walkthru.opensensor.shell.SettingsKey
import earth.walkthru.opensensor.shell.TodayKey
import platform.Foundation.NSBundle
import platform.Foundation.NSCurrentLocaleDidChangeNotification
import platform.Foundation.NSDate
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSTimeZone
import platform.Foundation.NSUserDefaults
import platform.Foundation.localTimeZone
import platform.Foundation.timeIntervalSince1970

/**
 * The one settings store for the process. DataStore allows one instance per file, so it lives
 * outside composition. The first read imports the two settings older builds kept in UserDefaults.
 */
private val settingsRepository: SettingsRepository by lazy { iosSettingsRepository() }

/**
 * Holds the Discover controller for the Discover entry, so it survives tab switches and is cleared
 * only when its entry leaves the back stack (the ViewModel entry decorator of the shell).
 */
private class DiscoverHolder : ViewModel() {
    val controller = DiscoverController(
        scope = viewModelScope,
        feed = bundledSampleFeed(),
        h3 = platformH3Grid(),
        prefs = UserDefaultsDiscoverPrefs(NSUserDefaults.standardUserDefaults),
        timeZoneId = NSTimeZone.localTimeZone.name,
        now = { (NSDate().timeIntervalSince1970 * 1000.0).toLong() },
    )
}

/** Holds the My devices controller for its entry, cleared when the entry leaves the back stack. */
private class DevicesHolder(bridge: DevicesBridge) : ViewModel() {
    val controller = bridge.controller(viewModelScope)
}

/** iOS collects while the app is open and cannot remove the system Bluetooth bond. */
private val IosDevices = DevicesPlatform(collectInBackground = false, explainSystemBond = true)

/**
 * The iOS root hosts the shared shell ([AppShell], `:feature:shell`), the same bottom bar, per-tab
 * back stacks and Back rules as Android. The start edge swipe is Back. Discover is always the start
 * tab (owner decision 2026-10-08). No other screen draws a bar. The My devices root is the shared
 * [MyDevicesScreen] over [devices]. Opening a sensor calls [openSensor] (Bluetooth is allowed by
 * then) and pushes its [devicePage], which receives the resolved display units (METRIC or IMPERIAL)
 * and the detail level from the shared Settings (`:feature:settings`, `:core:settings`).
 */
@Composable
internal fun AppRoot(
    devices: DevicesBridge,
    requestBluetooth: () -> Unit,
    openSensor: (String) -> Unit,
    devicePage: @Composable (id: String, units: UnitSystem, level: DetailLevel, onBack: () -> Unit) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val settings = remember { SettingsController(scope, settingsRepository) }
    val prefs by settings.prefs.collectAsState()
    // Off until the saved switch is read, so no request leaves the phone before then.
    val showPublic = prefs?.showPublicSensors == true
    // Automatic follows the iOS measurement system (Language and Region), live.
    val systemUnits = rememberSystemUnits()
    val unitChoice = prefs?.unitSystem ?: UnitSystem.AUTO
    val units = if (unitChoice == UnitSystem.AUTO) systemUnits else unitChoice
    val level = prefs?.detailLevel ?: DetailLevel.SIMPLE
    val version = remember { appVersion() }
    OpenSensorTheme {
        AppShell(
            entries = { nav ->
                entry<DiscoverKey> {
                    DiscoverEntry(networkAllowed = showPublic, units = units, onAddSensor = { nav.toMyDevicesRoot() })
                }
                entry<ScanKey> {
                    MyDevicesEntry(devices, level, requestBluetooth) { id, connect ->
                        if (connect && IosKnown.isPeripheral(id)) openSensor(id)
                        nav.push(TodayKey(id))
                    }
                }
                entry<TodayKey> { key ->
                    devicePage(key.address, units, level) { nav.pop() }
                }
                // iOS has no Smart Sync clock step yet, so its consent stays hidden.
                entry<SettingsKey> {
                    SettingsScreen(
                        controller = settings,
                        appVersion = version,
                        automaticUnits = systemUnits,
                        showClockSync = false,
                    )
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * My devices with its controller kept per entry. Bluetooth is asked for only from a tap, through the
 * shared on-demand flow. Swift creates the central manager in [requestBluetooth], which shows the
 * system prompt, and reports the answer through [CompanionState.bluetoothAuthorizationChanged].
 */
@Composable
private fun MyDevicesEntry(bridge: DevicesBridge, level: DetailLevel, requestBluetooth: () -> Unit, onOpen: (id: String, connect: Boolean) -> Unit) {
    val controller = viewModel { DevicesHolder(bridge) }.controller
    val state by controller.state.collectAsState()
    val companion = bridge.state
    val access = remember { RuntimeAccess(AccessKind.BLUETOOTH) }
    var asking by remember { mutableStateOf(false) }
    val openSettings = rememberOpenAppSettings()
    access.bind(
        AccessHooks.statusBased(
            status = { companion.bluetoothAccess },
            requestPermission = {
                asking = true
                requestBluetooth()
            },
            openSettings = openSettings,
            radioOn = { !companion.bluetoothPoweredOff },
            // iOS has no in-app switch for the radio. The list says it is off.
            requestTurnOn = { access.onTurnOnResult() },
        ),
    )
    LaunchedEffect(companion.bluetoothAccess, companion.bluetoothPoweredOff) {
        access.refresh()
        if (asking && companion.bluetoothAccess != AccessStatus.NOT_DETERMINED) {
            asking = false
            access.onPermissionResult()
        }
    }
    AccessDialogs(access, bluetoothAccessTexts())
    MyDevicesScreen(
        state = state,
        level = level,
        bluetooth = access,
        platform = IosDevices,
        onAction = { action ->
            when (action) {
                is DevicesAction.Open -> onOpen(action.id, action.connect)
                else -> controller.onAction(action)
            }
        },
    )
}

/** Discover with its controller kept per entry. Back, or the edge swipe, first returns an open sheet to peek. */
@Composable
private fun DiscoverEntry(networkAllowed: Boolean, units: UnitSystem, onAddSensor: () -> Unit) {
    val controller = viewModel { DiscoverHolder() }.controller
    val state by controller.state.collectAsState()
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = state.selected != null,
        onBackCompleted = { controller.selectCell(null) },
    )
    DiscoverScreen(
        controller = controller,
        networkAllowed = networkAllowed,
        onAddSensor = onAddSensor,
        units = units,
    )
}

/** The marketing version from Info.plist, as Android shows its version name. */
private fun appVersion(): String =
    NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: ""

/**
 * The phone's measurement system, recomputed when iOS posts [NSCurrentLocaleDidChangeNotification]
 * (Language and Region changed while the app runs), so Automatic units follow without a relaunch.
 */
@Composable
private fun rememberSystemUnits(): UnitSystem {
    var system by remember { mutableStateOf(systemUnitSystem()) }
    DisposableEffect(Unit) {
        val center = NSNotificationCenter.defaultCenter
        val observer = center.addObserverForName(
            name = NSCurrentLocaleDidChangeNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { _ -> system = systemUnitSystem() }
        // Catch a change made between the first read and the observer being added.
        system = systemUnitSystem()
        onDispose { center.removeObserver(observer) }
    }
    return system
}
