package earth.walkthru.opensensor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import earth.walkthru.opensensor.discover.DiscoverController
import earth.walkthru.opensensor.discover.DiscoverScreen
import earth.walkthru.opensensor.discover.UserDefaultsDiscoverPrefs
import earth.walkthru.opensensor.discover.bundledSampleFeed
import earth.walkthru.opensensor.history.HistoryController
import earth.walkthru.opensensor.history.HistoryScreen
import earth.walkthru.opensensor.history.HistoryTopBar
import earth.walkthru.opensensor.history.archiveStations
import earth.walkthru.opensensor.history.historyTitle
import earth.walkthru.opensensor.settings.SettingsController
import earth.walkthru.opensensor.settings.SettingsScreen
import earth.walkthru.opensensor.shell.AppShell
import earth.walkthru.opensensor.shell.DebugKey
import earth.walkthru.opensensor.shell.DiscoverKey
import earth.walkthru.opensensor.shell.HistoryKey
import earth.walkthru.opensensor.shell.ScanKey
import earth.walkthru.opensensor.shell.SettingsKey
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

/** Holds the Debug controller for one Debug entry, cleared when the entry leaves the back stack. */
private class DebugHolder(address: String, state: CompanionState, openLink: (String) -> SensorLink?) : ViewModel() {
    val controller = DebugController(viewModelScope, IosDebugSource(address, state, openLink, viewModelScope))
}

/**
 * The iOS root hosts the shared shell ([AppShell], `:feature:shell`), the same bottom bar, per-tab
 * back stacks and Back rules as Android. The start edge swipe is Back. Discover is always the start
 * tab (owner decision 2026-10-08). No other screen draws a bar. [companion] is the My devices root
 * and receives the resolved display units (METRIC or IMPERIAL) and the detail level from the shared
 * Settings (`:feature:settings`, `:core:settings`). At the Developer level [companion] also gets an
 * open Debug action, and the shared Debug screen (`:feature:debug`) borrows short links from Swift
 * through [openDebugLink] for the sensor [state] is connected to.
 */
@Composable
internal fun AppRoot(
    state: CompanionState,
    openDebugLink: (String) -> SensorLink?,
    companion: @Composable (UnitSystem, DetailLevel, ((String) -> Unit)?) -> Unit,
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
                    val openDebug: ((String) -> Unit)? = if (level.developer) { address -> nav.openDebug(address, level.developer) } else null
                    Box(Modifier.fillMaxSize()) {
                        companion(units, level, openDebug)
                        HistoryShortcut(onClick = { nav.push(HistoryKey(PHONE_ARCHIVE)) }, modifier = Modifier.align(Alignment.TopEnd))
                    }
                }
                entry<HistoryKey> {
                    HistoryEntry(units = units, level = level, onBack = { nav.pop() })
                }
                entry<DebugKey> { key ->
                    val controller = viewModel(key = "debug-${key.address}") { DebugHolder(key.address, state, openDebugLink) }.controller
                    DebugScreen(
                        controller = controller,
                        address = key.address,
                        developer = prefs?.detailLevel?.developer,
                        onBack = { nav.popIfTop { it is DebugKey } },
                    )
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

/** A compact History button over the top end of My devices, the iOS way into History until device sections are shared. */
@Composable
private fun HistoryShortcut(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val title = historyTitle()
    FloatingIconButton(
        onClick = onClick,
        contentDescription = title,
        modifier = modifier.statusBarsPadding().padding(FloatingControl.Margin),
    ) {
        Icon(AppIcons.Schedule, contentDescription = null)
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
