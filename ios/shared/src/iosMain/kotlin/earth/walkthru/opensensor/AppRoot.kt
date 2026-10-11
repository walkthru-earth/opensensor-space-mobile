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
import earth.walkthru.opensensor.device.DeviceConfigAction
import earth.walkthru.opensensor.device.DeviceConfigController
import earth.walkthru.opensensor.device.DeviceConfigScreen
import earth.walkthru.opensensor.device.LanAccess
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
import earth.walkthru.opensensor.shell.DeviceSettingsKey
import earth.walkthru.opensensor.shell.DiscoverKey
import earth.walkthru.opensensor.shell.FileMetricsKey
import earth.walkthru.opensensor.shell.FilesKey
import earth.walkthru.opensensor.shell.HistoryKey
import earth.walkthru.opensensor.shell.ScanKey
import earth.walkthru.opensensor.shell.SettingsKey
import earth.walkthru.opensensor.shell.ShellBottomBar
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

/** One page's hold on a Bluetooth sensor, released when its navigation entry is removed. */
private class PageLease(private val leases: PeripheralLeases, private val id: String) : ViewModel() {
    init {
        leases.acquire(id)
    }

    override fun onCleared() = leases.release(id)
}

/** Holds this entry's lease on [id], so popping the last page for the sensor offers its link back. */
@Composable
private fun LeasePeripheral(leases: PeripheralLeases, id: String) {
    viewModel(key = "page-lease-$id") { PageLease(leases, id) }
}

/** Holds the My devices controller for its entry, cleared when the entry leaves the back stack. */
private class DevicesHolder(bridge: DevicesBridge) : ViewModel() {
    val controller = bridge.controller(viewModelScope)
}

/** iOS collects while the app is open and cannot remove the system Bluetooth bond. */
private val IosDevices = DevicesPlatform(collectInBackground = false, explainSystemBond = true)

/** Holds the Debug controller for one Debug entry, cleared when the entry leaves the back stack. */
private class DebugHolder(address: String, state: CompanionState, openLink: (String) -> IosLink?) : ViewModel() {
    val controller = DebugController(viewModelScope, IosDebugSource(address, state, openLink, viewModelScope))
}

/** Holds the Device settings controller of one sensor for its entry, cleared when the entry leaves the back stack. */
private class DeviceSettingsHolder(address: String, devices: DevicesBridge, links: DeviceConfigLinks) : ViewModel() {
    val controller = DeviceConfigController(
        scope = viewModelScope,
        source = IosDeviceConfigSource(address, devices.state, devices, links, viewModelScope),
        detailLevel = settingsRepository.detailLevel,
        // Nothing else reads the config on iOS, so the page reads it once the link is ready.
        readOnReady = true,
    )
}

/**
 * The iOS root hosts the shared shell ([AppShell], `:feature:shell`), the same bottom bar, per-tab
 * back stacks and Back rules as Android. The start edge swipe is Back. Discover is always the start
 * tab (owner decision 2026-10-08). No other screen draws a bar. The My devices root is the shared
 * [MyDevicesScreen] over [devices]. Opening a sensor calls [openSensor] (Bluetooth is allowed by
 * then) and pushes its [devicePage], which receives the resolved display units (METRIC or IMPERIAL)
 * and the detail level from the shared Settings (`:feature:settings`, `:core:settings`). At the
 * Developer level the device page also gets an open Debug action, and the shared Debug screen
 * (`:feature:debug`) takes leases of the connection's link from Swift through [openDebugLink]. A Bluetooth sensor
 * opens on the shared Today over [today], and its Device section is the [devicePage]. Today and
 * Device each hold a lease on that sensor, and popping the last of them calls [closeIdle] once.
 * The companion page of a Bluetooth sensor opens the shared Device settings (`:feature:device`),
 * which take leases of the connection's link through [configLinks] and hold the same lease.
 */
@Composable
internal fun AppRoot(
    devices: DevicesBridge,
    today: CompanionToday,
    requestBluetooth: () -> Unit,
    openSensor: (String) -> Unit,
    openLanOnly: (String) -> Unit,
    openDebugLink: (String) -> IosLink?,
    closeIdle: (String) -> Unit,
    configLinks: DeviceConfigLinks,
    filesActions: DeviceFilesActions,
    devicePage: DevicePageContent,
) {
    val scope = rememberCoroutineScope()
    val settings = remember { SettingsController(scope, settingsRepository, IosArchive.storageControls) }
    val prefs by settings.prefs.collectAsState()
    // Off until the saved switch is read, so no request leaves the phone before then.
    val showPublic = prefs?.showPublicSensors == true
    // Automatic follows the iOS measurement system (Language and Region), live.
    val systemUnits = rememberSystemUnits()
    val unitChoice = prefs?.unitSystem ?: UnitSystem.AUTO
    val units = if (unitChoice == UnitSystem.AUTO) systemUnits else unitChoice
    val level = prefs?.detailLevel ?: DetailLevel.SIMPLE
    val version = remember { appVersion() }
    val leases = remember { PeripheralLeases(closeIdle) }
    OpenSensorTheme {
        AppShell(
            entries = { nav ->
                entry<DiscoverKey> {
                    DiscoverEntry(networkAllowed = showPublic, level = level, units = units, onAddSensor = { nav.toMyDevicesRoot() })
                }
                entry<ScanKey> {
                    MyDevicesEntry(
                        bridge = devices,
                        level = level,
                        requestBluetooth = requestBluetooth,
                        onFiles = { nav.push(FilesKey(PHONE_ARCHIVE)) },
                        onHistory = { nav.push(HistoryKey(PHONE_ARCHIVE)) },
                    ) { id, connect ->
                        val peripheral = when {
                            IosKnown.isPeripheral(id) -> id
                            id.startsWith(IosKnown.WIFI_PREFIX) -> devices.peripheralForLan(id.removePrefix(IosKnown.WIFI_PREFIX))
                            else -> null
                        }
                        if (peripheral != null) {
                            if (connect) openSensor(peripheral) else openLanOnly(peripheral)
                            nav.push(TodayKey(peripheral))
                        }
                    }
                }
                // An enrolled sensor opens the same Today screen over Bluetooth or authenticated Wi-Fi.
                // A Wi-Fi-only discovery without Bluetooth enrollment cannot open a sensor page.
                entry<TodayKey> { key ->
                    if (IosKnown.isPeripheral(key.address)) {
                        LeasePeripheral(leases, key.address)
                        TodayEntry(key.address, today, devices, settingsRepository, units) { action ->
                            when (action) {
                                TodayAction.OpenHistory -> nav.openSection(HistoryKey(key.address))
                                TodayAction.OpenFiles -> nav.openSection(FilesKey(key.address))
                                TodayAction.OpenDevice -> nav.push(DeviceKey(key.address))
                                // Debug borrows the authenticated link for this sensor, whether Wi-Fi or Bluetooth.
                                TodayAction.OpenDebug -> {
                                    val lan = today.state.connectedLanForStation(devices.stationFor(key.address))
                                    val device = lan?.let(today.state.lanInfo::get)?.dev
                                        ?: today.state.info?.dev?.takeIf { today.state.selectedId == key.address }
                                    device?.let { nav.openDebug(it, level.developer) }
                                }
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
                    if (IosKnown.isPeripheral(key.address)) LeasePeripheral(leases, key.address)
                    DevicePage(key.address, units, level, nav, devicePage)
                }
                entry<DeviceSettingsKey> { key ->
                    LeasePeripheral(leases, key.address)
                    DeviceSettingsEntry(key.address, devices, configLinks, onBack = { nav.popIfTop { it == key } })
                }
                entry<HistoryKey> { key ->
                    HistoryEntry(address = key.address, devices = devices, units = units, level = level, onBack = { nav.pop() })
                }
                entry<FilesKey> { key ->
                    FilesEntry(
                        address = key.address,
                        devices = devices,
                        actions = filesActions,
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
                    )
                }
            },
            modifier = Modifier.fillMaxSize(),
            bottomBar = { nav ->
                ShellBottomBar(nav)
                // A tapped Auto-sync summary opens My devices (#23).
                val openDevices by devices.openRequested.collectAsState()
                LaunchedEffect(openDevices) {
                    if (openDevices) {
                        nav.toMyDevicesRoot()
                        devices.openHandled()
                    }
                }
            },
        )
    }
}

/**
 * Device settings of one Bluetooth sensor. Local network is asked for from the Sync over Wi-Fi card
 * by browsing for sensors, the iOS prompt. The sticker card scans with VisionKit where the hardware
 * has it (#21), asking for the camera on the Scan tap, and the sticker can always be typed.
 */
@Composable
private fun DeviceSettingsEntry(address: String, devices: DevicesBridge, links: DeviceConfigLinks, onBack: () -> Unit) {
    val controller = viewModel(key = "device-settings-$address") { DeviceSettingsHolder(address, devices, links) }.controller
    val sticker = rememberIosStickerScan(links.scanSticker)
    DeviceConfigScreen(
        controller = controller,
        lanAccess = LanAccess(granted = !devices.state.lanAccessDenied, requestable = true),
        onBack = onBack,
        onPlatformAction = { action ->
            when (action) {
                DeviceConfigAction.RequestLanAccess -> links.scanLan()
                DeviceConfigAction.ScanSticker -> sticker.start()
                else -> Unit
            }
        },
        scan = sticker.scan,
        canScan = sticker.available,
    )
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
        onOpenLocalNetworkSettings = openSettings,
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

/** Discover with its controller kept per entry. The shared screen takes Back, or the edge swipe, while a sheet is open. */
@Composable
private fun DiscoverEntry(networkAllowed: Boolean, level: DetailLevel, units: UnitSystem, onAddSensor: () -> Unit) {
    val controller = viewModel { DiscoverHolder() }.controller
    DiscoverScreen(
        controller = controller,
        networkAllowed = networkAllowed,
        level = level,
        onAddSensor = onAddSensor,
        units = units,
    )
}

/**
 * History reads the same offline index the sync runner writes. A sensor entry offers only that
 * sensor's station generations; the My devices shortcut offers the whole phone archive.
 */
private class HistoryHolder(address: String, devices: DevicesBridge) : ViewModel() {
    private val wholePhone = address == PHONE_ARCHIVE
    private val stations = if (wholePhone) {
        IosArchive.index.observeAll().map(::archiveStations)
    } else {
        devices.known.map { devices.stationsFor(address).asReversed() }
    }
    val controller = HistoryController(
        scope = viewModelScope,
        reader = IosArchive.reader,
        tiers = IosArchive.tiers,
        index = IosArchive.index,
        currentStation = if (wholePhone) stations.map { it.lastOrNull() } else devices.known.map { devices.stationFor(address) },
        stations = stations,
    )
}

/** A separate key for the whole phone archive, reached from My devices. */
private const val PHONE_ARCHIVE = "archive"

/** Offline History. Index repair runs from launch ([sharedSensorSync]) and is started here too. */
@Composable
private fun HistoryEntry(address: String, devices: DevicesBridge, units: UnitSystem, level: DetailLevel, onBack: () -> Unit) {
    remember { IosArchive.startIndexRepair() }
    val controller = viewModel(key = "history-$address") { HistoryHolder(address, devices) }.controller
    HistoryScreen(
        controller = controller,
        units = remember(units) { UnitFormatter(units) },
        level = level,
        topBar = { HistoryTopBar(onBack = onBack) },
    )
}

/**
 * Sensor Files has a station owner and device manifest; the My devices shortcut has no owner and
 * shows the whole phone archive. Exports stage into a temporary run removed after the sheet closes.
 */
private class FilesHolder(address: String, devices: DevicesBridge, actions: DeviceFilesActions) : ViewModel() {
    val controller = FilesController(
        scope = viewModelScope,
        store = IosArchive.store,
        index = IosArchive.index,
        space = IosExportSpace.inTemporaryDirectory(),
        // A forgotten sensor must never fall through to the whole-phone archive.
        owner = if (address == PHONE_ARCHIVE) flowOf(null) else devices.known.map { devices.stationFor(address) ?: "" },
        daily = null,
    )
    val sensor = if (address == PHONE_ARCHIVE) null else IosDeviceFiles(address, devices, actions, viewModelScope)
}

/** Holds the inspector of one archived file for its entry. */
private class FileMetricsHolder : ViewModel() {
    val controller = FileMetricsController(viewModelScope, IosArchive.store)
}

/** Files of one sensor or the whole phone archive, with the shared inspector and export actions. */
@Composable
private fun FilesEntry(
    address: String,
    devices: DevicesBridge,
    actions: DeviceFilesActions,
    level: DetailLevel,
    onBack: () -> Unit,
    onOpenFileMetrics: (String, String) -> Unit,
) {
    val holder = viewModel(key = "files-$address") { FilesHolder(address, devices, actions) }
    val device by holder.sensor?.state?.collectAsState() ?: remember { mutableStateOf(null) }
    FilesScreen(
        controller = holder.controller,
        level = level,
        onOpenFileMetrics = onOpenFileMetrics,
        onBack = onBack,
        device = device,
        onDeviceAction = { holder.sensor?.onAction(it) },
    )
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
    devicePage: DevicePageContent,
) {
    Box(Modifier.fillMaxSize()) {
        val openDebug: ((String) -> Unit)? = if (level.developer) { device -> nav.openDebug(device, level.developer) } else null
        val openSettings: (() -> Unit)? = if (IosKnown.isPeripheral(address)) ({ nav.push(DeviceSettingsKey(address)) }) else null
        devicePage(address, units, level, openDebug, openSettings) { nav.pop() }
        ArchiveShortcuts(
            onFiles = { nav.openSection(FilesKey(address)) },
            onHistory = { nav.openSection(HistoryKey(address)) },
            modifier = Modifier.align(Alignment.TopEnd),
        )
    }
}

/** The companion page body Swift's window supplies: the sensor id, display units, detail level, Debug, Device settings and Back. */
internal typealias DevicePageContent =
    @Composable (id: String, units: UnitSystem, level: DetailLevel, openDebug: ((String) -> Unit)?, openSettings: (() -> Unit)?, onBack: () -> Unit) -> Unit

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
