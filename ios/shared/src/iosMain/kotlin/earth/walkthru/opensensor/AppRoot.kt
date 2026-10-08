package earth.walkthru.opensensor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import earth.walkthru.aqsync.publicfeed.platformH3Grid
import earth.walkthru.aqsync.settings.DetailLevel
import earth.walkthru.aqsync.settings.SettingsRepository
import earth.walkthru.aqsync.settings.iosSettingsRepository
import earth.walkthru.aqsync.units.UnitSystem
import earth.walkthru.aqsync.units.systemUnitSystem
import earth.walkthru.opensensor.designsystem.AppIcons
import earth.walkthru.opensensor.designsystem.BottomBarTab
import earth.walkthru.opensensor.designsystem.BrandBottomBar
import earth.walkthru.opensensor.designsystem.FloatingControl
import earth.walkthru.opensensor.designsystem.OpenSensorTheme
import earth.walkthru.opensensor.discover.DiscoverController
import earth.walkthru.opensensor.discover.DiscoverScreen
import earth.walkthru.opensensor.discover.UserDefaultsDiscoverPrefs
import earth.walkthru.opensensor.discover.bundledSampleFeed
import earth.walkthru.opensensor.settings.SettingsController
import earth.walkthru.opensensor.settings.SettingsScreen
import earth.walkthru.opensensor.settings.settingsTitle
import platform.Foundation.NSBundle
import platform.Foundation.NSCurrentLocaleDidChangeNotification
import platform.Foundation.NSDate
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSTimeZone
import platform.Foundation.NSUserDefaults
import platform.Foundation.localTimeZone
import platform.Foundation.timeIntervalSince1970

private enum class Tab { DISCOVER, MY_DEVICES, SETTINGS }

/**
 * The one settings store for the process. DataStore allows one instance per file, so it lives
 * outside composition. The first read imports the two settings older builds kept in UserDefaults.
 */
private val settingsRepository: SettingsRepository by lazy { iosSettingsRepository() }

/**
 * The iOS root: one bottom bar with three tabs, Discover, My devices and Settings, as on Android.
 * Discover is always the start tab (owner decision 2026-10-08). No other screen draws a bar.
 * [companion] receives the resolved display units (METRIC or IMPERIAL) and the detail level from
 * the shared Settings (`:feature:settings`, `:core:settings`).
 */
@Composable
internal fun AppRoot(companion: @Composable (UnitSystem, DetailLevel) -> Unit) {
    var tab by rememberSaveable { mutableStateOf(Tab.DISCOVER) }
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
    val controller = remember {
        DiscoverController(
            scope = scope,
            feed = bundledSampleFeed(),
            h3 = platformH3Grid(),
            prefs = UserDefaultsDiscoverPrefs(NSUserDefaults.standardUserDefaults),
            timeZoneId = NSTimeZone.localTimeZone.name,
            now = { (NSDate().timeIntervalSince1970 * 1000.0).toLong() },
        )
    }
    OpenSensorTheme {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Surface(Modifier.weight(1f), color = MaterialTheme.colorScheme.background) {
                when (tab) {
                    Tab.DISCOVER -> DiscoverScreen(
                        controller = controller,
                        networkAllowed = showPublic,
                        onAddSensor = { tab = Tab.MY_DEVICES },
                        units = units,
                    )
                    Tab.MY_DEVICES -> companion(units, level)
                    // iOS has no Smart Sync clock step yet, so its consent stays hidden.
                    Tab.SETTINGS -> SettingsScreen(
                        controller = settings,
                        appVersion = appVersion(),
                        automaticUnits = systemUnits,
                        showClockSync = false,
                    )
                }
            }
            BrandBottomBar(tabs = tabs(), selected = tab, onSelect = { tab = it })
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

/** The same three tabs as Android, on the shared [BrandBottomBar]. */
@Composable
private fun tabs(): List<BottomBarTab<Tab>> = listOf(
    BottomBarTab(Tab.DISCOVER, "Discover", AppIcons.Outlined.Map),
    BottomBarTab(Tab.MY_DEVICES, "My devices", AppIcons.Outlined.Sensors),
    BottomBarTab(Tab.SETTINGS, settingsTitle(), AppIcons.Outlined.Settings),
)

