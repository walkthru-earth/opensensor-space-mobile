package earth.walkthru.opensensor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import earth.walkthru.aqsync.archive.IosExportSpace
import earth.walkthru.aqsync.publicfeed.platformH3Grid
import earth.walkthru.aqsync.settings.DetailLevel
import earth.walkthru.aqsync.settings.SettingsRepository
import earth.walkthru.aqsync.settings.iosSettingsRepository
import earth.walkthru.aqsync.units.UnitFormatter
import earth.walkthru.aqsync.units.UnitSystem
import earth.walkthru.aqsync.units.systemUnitSystem
import earth.walkthru.opensensor.debug.DebugController
import earth.walkthru.opensensor.debug.DebugScreen
import earth.walkthru.opensensor.designsystem.AppIcons
import earth.walkthru.opensensor.designsystem.FloatingControl
import earth.walkthru.opensensor.designsystem.FloatingIconButton
import earth.walkthru.opensensor.designsystem.OpenSensorTheme
import earth.walkthru.opensensor.devices.DevicesAction
import earth.walkthru.opensensor.devices.DevicesPlatform
import earth.walkthru.opensensor.devices.MyDevicesScreen
import earth.walkthru.opensensor.devices.bluetoothAccessTexts
import earth.walkthru.opensensor.discover.DiscoverController
import earth.walkthru.opensensor.discover.DiscoverScreen
import earth.walkthru.opensensor.discover.UserDefaultsDiscoverPrefs
import earth.walkthru.opensensor.discover.bundledSampleFeed
import earth.walkthru.opensensor.files.FileMetricsController
import earth.walkthru.opensensor.files.FileMetricsScreen
import earth.walkthru.opensensor.files.FilesController
import earth.walkthru.opensensor.files.FilesScreen
import earth.walkthru.opensensor.files.filesTitle
import earth.walkthru.opensensor.history.HistoryController
import earth.walkthru.opensensor.history.HistoryScreen
import earth.walkthru.opensensor.history.HistoryTopBar
import earth.walkthru.opensensor.history.archiveStations
import earth.walkthru.opensensor.history.historyTitle
import earth.walkthru.opensensor.permissions.AccessDialogs
import earth.walkthru.opensensor.permissions.AccessHooks
import earth.walkthru.opensensor.permissions.AccessKind
import earth.walkthru.opensensor.permissions.AccessStatus
import earth.walkthru.opensensor.permissions.RuntimeAccess
import earth.walkthru.opensensor.permissions.openAppSettings
import earth.walkthru.opensensor.permissions.rememberOpenAppSettings
import earth.walkthru.opensensor.settings.SettingsController
import earth.walkthru.opensensor.settings.SettingsScreen
import earth.walkthru.opensensor.shell.AppShell
import earth.walkthru.opensensor.shell.DebugKey
import earth.walkthru.opensensor.shell.DeviceKey
import earth.walkthru.opensensor.shell.DiscoverKey
import earth.walkthru.opensensor.shell.FileMetricsKey
import earth.walkthru.opensensor.shell.FilesKey
import earth.walkthru.opensensor.shell.HistoryKey
import earth.walkthru.opensensor.shell.ScanKey
import earth.walkthru.opensensor.shell.SettingsKey
import earth.walkthru.opensensor.shell.TabNavigator
import earth.walkthru.opensensor.shell.TodayKey
import earth.walkthru.opensensor.shell.TopTab
import earth.walkthru.opensensor.today.TodayAction
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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

/** Holds the Debug controller for one Debug entry, cleared when the entry leaves the back stack. */
private class DebugHolder(address: String, state: CompanionState, openLink: (String) -> SensorLink?) : ViewModel() {
    val controller = DebugController(viewModelScope, IosDebugSource(address, state, openLink, viewModelScope))
}

/**
 * The iOS root hosts the shared shell ([AppShell], `:feature:shell`), the same bottom bar, per-tab
 * back stacks and Back rules as Android. The start edge swipe is Back. Discover is always the start
 * tab (owner decision 2026-10-08). No other screen draws a bar. The My devices root is the shared
 * [MyDevicesScreen] over [devices]. Opening a sensor calls [openSensor] (Bluetooth is allowed by
 * then) and pushes its [devicePage], which receives the resolved display units (METRIC or IMPERIAL)
 * and the detail level from the shared Settings (`:feature:settings`, `:core:settings`). At the
 * Developer level the device page also gets an open Debug action, and the shared Debug screen
 * (`:feature:debug`) borrows short links from Swift through [openDebugLink]. A Bluetooth sensor
 * opens on the shared Today over [today], and its Device section is the [devicePage].
 */
@Composable
internal fun AppRoot(
    devices: DevicesBridge,
    today: CompanionToday,
    requestBluetooth: () -> Unit,
    openSensor: (String) -> Unit,
    openDebugLink: (String) -> SensorLink?,
    devicePage: @Composable (id: String, units: UnitSystem, level: DetailLevel, openDebug: ((String) -> Unit)?, onBack: () -> Unit) -> Unit,
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
                    MyDevicesEntry(
                        bridge = devices,
                        level = level,
                        requestBluetooth = requestBluetooth,
                        onFiles = { nav.push(FilesKey(PHONE_ARCHIVE)) },
                        onHistory = { nav.push(HistoryKey(PHONE_ARCHIVE)) },
                    ) { id, connect ->
                        if (connect && IosKnown.isPeripheral(id)) openSensor(id)
                        nav.push(TodayKey(id))
                    }
                }
                // The shared Today (`:feature:today`) for a Bluetooth sensor. A Wi-Fi only sensor keeps the
                // companion page, which holds its Wi-Fi session. Device opens that page for a Bluetooth sensor too.
                entry<TodayKey> { key ->
                    if (IosKnown.isPeripheral(key.address)) {
                        TodayEntry(key.address, today, settingsRepository, units) { action ->
                            when (action) {
                                TodayAction.OpenHistory -> nav.push(HistoryKey(PHONE_ARCHIVE))
                                TodayAction.OpenFiles -> nav.push(FilesKey(PHONE_ARCHIVE))
                                TodayAction.OpenDevice -> nav.push(DeviceKey(key.address))
                                // Debug talks to the hardware id, the same key the companion page passes.
                                TodayAction.OpenDebug -> today.state.info?.dev?.let { nav.openDebug(it, level.developer) }
                                TodayAction.OpenSettings -> nav.select(TopTab.SETTINGS)
                                TodayAction.OpenBluetoothSettings -> openAppSettings()
                                else -> nav.popIfTop { it == key }
                            }
                        }
                    } else {
                        DevicePage(key.address, units, level, nav, devicePage)
                    }
                }
                entry<DeviceKey> { key ->
                    DevicePage(key.address, units, level, nav, devicePage)
                }
                entry<HistoryKey> {
                    HistoryEntry(units = units, level = level, onBack = { nav.pop() })
                }
                entry<FilesKey> {
                    FilesEntry(
                        level = level,
                        onBack = { nav.pop() },
                        onOpenFileMetrics = { station, name -> nav.push(FileMetricsKey(station, name)) },
                    )
                }
                entry<FileMetricsKey> { key ->
                    FileMetricsEntry(station = key.station, name = key.name, level = level, onBack = { nav.pop() })
                }
                entry<DebugKey> { key ->
                    val controller = viewModel(key = "debug-${key.address}") { DebugHolder(key.address, devices.state, openDebugLink) }.controller
                    DebugScreen(
                        controller = controller,
                        address = key.address,
                        developer = prefs?.detailLevel?.developer,
                        onBack = { nav.popIfTop { it is DebugKey } },
                    )
                }
                // Smart Sync on Today asks before setting the clock, so the consent shows as on Android.
                entry<SettingsKey> {
                    SettingsScreen(
                        controller = settings,
                        appVersion = version,
                        automaticUnits = systemUnits,
                        showClockSync = true,
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
private fun MyDevicesEntry(
    bridge: DevicesBridge,
    level: DetailLevel,
    requestBluetooth: () -> Unit,
    onFiles: () -> Unit,
    onHistory: () -> Unit,
    onOpen: (id: String, connect: Boolean) -> Unit,
) {
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
        topActions = {
            IconButton(onClick = onFiles) { Icon(AppIcons.Storage, contentDescription = filesTitle()) }
            IconButton(onClick = onHistory) { Icon(AppIcons.Schedule, contentDescription = historyTitle()) }
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

/**
 * Holds the History controller for the History entry. iOS History reads the whole phone archive, the
 * same index the sync runner writes ([IosArchive]). Its station chips are the archived stations,
 * oldest latest reading first, and it opens on the one with the latest reading.
 */
private class HistoryHolder : ViewModel() {
    private val stations = IosArchive.index.observeAll().map(::archiveStations)
    val controller = HistoryController(
        scope = viewModelScope,
        reader = IosArchive.reader,
        tiers = IosArchive.tiers,
        index = IosArchive.index,
        currentStation = stations.map { it.lastOrNull() },
        stations = stations,
    )
}

/** The History key of iOS. History reads the phone archive, not one device, so the address is a fixed name. */
private const val PHONE_ARCHIVE = "archive"

/** History of the phone archive, offline. Index repair runs from launch ([sharedSensorSync]) and is started here too. */
@Composable
private fun HistoryEntry(units: UnitSystem, level: DetailLevel, onBack: () -> Unit) {
    remember { IosArchive.startIndexRepair() }
    val controller = viewModel { HistoryHolder() }.controller
    HistoryScreen(
        controller = controller,
        units = remember(units) { UnitFormatter(units) },
        level = level,
        topBar = { HistoryTopBar(onBack = onBack) },
    )
}

/**
 * Holds the Files controller for the Files entry. iOS Files lists the whole phone archive, with no
 * owner, so per-file actions come from archive rows only. Exports stage into a fresh run under the
 * temporary directory, removed once the sheet closes (B7). iOS derives no daily files yet.
 */
private class FilesHolder : ViewModel() {
    val controller = FilesController(
        scope = viewModelScope,
        store = IosArchive.store,
        index = IosArchive.index,
        space = IosExportSpace.inTemporaryDirectory(),
        owner = flowOf(null),
        daily = null,
    )
}

/** Holds the inspector of one archived file for its entry. */
private class FileMetricsHolder : ViewModel() {
    val controller = FileMetricsController(viewModelScope, IosArchive.store)
}

/** Files of the phone archive, offline: the list, the inspector and every export. */
@Composable
private fun FilesEntry(level: DetailLevel, onBack: () -> Unit, onOpenFileMetrics: (String, String) -> Unit) {
    val controller = viewModel { FilesHolder() }.controller
    FilesScreen(controller = controller, level = level, onOpenFileMetrics = onOpenFileMetrics, onBack = onBack)
}

/** The inspector of one archived file. It only reads the file. */
@Composable
private fun FileMetricsEntry(station: String, name: String, level: DetailLevel, onBack: () -> Unit) {
    val controller = viewModel(key = "$station/$name") { FileMetricsHolder() }.controller
    LaunchedEffect(station, name) { controller.load(station, name) }
    val state by controller.state.collectAsState()
    FileMetricsScreen(state = state, level = level, name = name, onBack = onBack)
}

/**
 * The companion page of one sensor: the Wi-Fi session, Wi-Fi access and, at the Developer level,
 * Debug. Files and History stay one tap away.
 */
@Composable
private fun DevicePage(
    address: String,
    units: UnitSystem,
    level: DetailLevel,
    nav: TabNavigator,
    devicePage: @Composable (id: String, units: UnitSystem, level: DetailLevel, openDebug: ((String) -> Unit)?, onBack: () -> Unit) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        val openDebug: ((String) -> Unit)? = if (level.developer) { device -> nav.openDebug(device, level.developer) } else null
        devicePage(address, units, level, openDebug) { nav.pop() }
        ArchiveShortcuts(
            onFiles = { nav.push(FilesKey(PHONE_ARCHIVE)) },
            onHistory = { nav.push(HistoryKey(PHONE_ARCHIVE)) },
            modifier = Modifier.align(Alignment.TopEnd),
        )
    }
}

/** Compact Files and History buttons over the top end of the companion page, the iOS way into the phone archive. */
@Composable
private fun ArchiveShortcuts(onFiles: () -> Unit, onHistory: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.statusBarsPadding().padding(FloatingControl.Margin),
        horizontalArrangement = Arrangement.spacedBy(FloatingControl.Margin),
    ) {
        FloatingIconButton(onClick = onFiles, contentDescription = filesTitle()) {
            Icon(AppIcons.Storage, contentDescription = null)
        }
        FloatingIconButton(onClick = onHistory, contentDescription = historyTitle()) {
            Icon(AppIcons.Schedule, contentDescription = null)
        }
    }
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
