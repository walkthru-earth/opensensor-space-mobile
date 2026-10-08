package earth.walkthru.opensensor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import earth.walkthru.aqsync.units.UnitSystem
import earth.walkthru.aqsync.units.systemUnitSystem
import earth.walkthru.opensensor.designsystem.AppIcons
import earth.walkthru.opensensor.designsystem.BottomBarTab
import earth.walkthru.opensensor.designsystem.BrandBottomBar
import earth.walkthru.opensensor.designsystem.CardShape
import earth.walkthru.opensensor.designsystem.FloatingControl
import earth.walkthru.opensensor.designsystem.OpenSensorTheme
import earth.walkthru.opensensor.discover.DiscoverController
import earth.walkthru.opensensor.discover.DiscoverScreen
import earth.walkthru.opensensor.discover.DiscoverStrings
import earth.walkthru.opensensor.discover.UserDefaultsDiscoverPrefs
import earth.walkthru.opensensor.discover.bundledSampleFeed
import platform.Foundation.NSCurrentLocaleDidChangeNotification
import platform.Foundation.NSDate
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSTimeZone
import platform.Foundation.NSUserDefaults
import platform.Foundation.localTimeZone
import platform.Foundation.timeIntervalSince1970

private enum class Tab { DISCOVER, MY_DEVICES, SETTINGS }

private const val SHOW_PUBLIC_KEY = "discover.showPublicSensors"

/** Display units, the [UnitSystem] name. Missing means Automatic. */
private const val UNIT_SYSTEM_KEY = "settings.unitSystem"

/**
 * The iOS root: one bottom bar with three tabs, Discover, My devices and Settings, as on Android.
 * Discover is always the start tab (owner decision 2026-10-08). No other screen draws a bar.
 * [companion] receives the resolved display units (METRIC or IMPERIAL) from Settings.
 */
@Composable
internal fun AppRoot(companion: @Composable (UnitSystem) -> Unit) {
    val defaults = remember { NSUserDefaults.standardUserDefaults }
    var tab by rememberSaveable { mutableStateOf(Tab.DISCOVER) }
    var showPublic by remember { mutableStateOf(defaults.objectForKey(SHOW_PUBLIC_KEY) == null || defaults.boolForKey(SHOW_PUBLIC_KEY)) }
    var unitChoice by remember { mutableStateOf(UnitSystem.parse(defaults.stringForKey(UNIT_SYSTEM_KEY))) }
    // Automatic follows the iOS measurement system (Language and Region), live.
    val systemUnits = rememberSystemUnits()
    val units = if (unitChoice == UnitSystem.AUTO) systemUnits else unitChoice
    val scope = rememberCoroutineScope()
    val controller = remember {
        DiscoverController(
            scope = scope,
            feed = bundledSampleFeed(),
            h3 = platformH3Grid(),
            prefs = UserDefaultsDiscoverPrefs(defaults),
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
                        strings = DiscoverStrings.English,
                        networkAllowed = showPublic,
                        onAddSensor = { tab = Tab.MY_DEVICES },
                        units = units,
                    )
                    Tab.MY_DEVICES -> companion(units)
                    Tab.SETTINGS -> IosSettings(
                        showPublic = showPublic,
                        onShowPublic = { on ->
                            showPublic = on
                            defaults.setBool(on, SHOW_PUBLIC_KEY)
                        },
                        unitChoice = unitChoice,
                        resolvedUnits = units,
                        onUnitChoice = { choice ->
                            unitChoice = choice
                            defaults.setObject(choice.name, UNIT_SYSTEM_KEY)
                        },
                    )
                }
            }
            BrandBottomBar(tabs = Tabs, selected = tab, onSelect = { tab = it })
        }
    }
}

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
private val Tabs = listOf(
    BottomBarTab(Tab.DISCOVER, "Discover", AppIcons.Outlined.Map),
    BottomBarTab(Tab.MY_DEVICES, "My devices", AppIcons.Outlined.Sensors),
    BottomBarTab(Tab.SETTINGS, "Settings", AppIcons.Outlined.Settings),
)

/** The iOS settings so far: the switch for Discover network use (AGENTS.md boundary) and the units. */
@Composable
private fun IosSettings(
    showPublic: Boolean,
    onShowPublic: (Boolean) -> Unit,
    unitChoice: UnitSystem,
    resolvedUnits: UnitSystem,
    onUnitChoice: (UnitSystem) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp, bottom = 32.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))
        SettingsCard {
            Text("Units", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                UnitSystem.entries.forEachIndexed { i, system ->
                    SegmentedButton(
                        selected = unitChoice == system,
                        onClick = { onUnitChoice(system) },
                        shape = SegmentedButtonDefaults.itemShape(i, UnitSystem.entries.size, MaterialTheme.shapes.small),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = FloatingControl.Selected,
                            activeContentColor = FloatingControl.OnSelected,
                            activeBorderColor = colors.outlineVariant,
                            inactiveContainerColor = colors.surface,
                            inactiveContentColor = colors.onSurface,
                            inactiveBorderColor = colors.outlineVariant,
                        ),
                        label = { Text(unitLabel(system)) },
                    )
                }
            }
            if (unitChoice == UnitSystem.AUTO) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Follows your phone's region, now ${unitLabel(resolvedUnits)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        // The one switch for Discover network use (feed and basemap), AGENTS.md boundary.
        SettingsCard {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = FloatingControl.Size)
                    .toggleable(value = showPublic, onValueChange = onShowPublic, role = Role.Switch),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Show public sensors", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(
                    checked = showPublic,
                    onCheckedChange = null,
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = FloatingControl.Selected,
                        checkedThumbColor = FloatingControl.OnSelected,
                        checkedBorderColor = FloatingControl.Selected,
                        uncheckedTrackColor = colors.surface,
                        uncheckedThumbColor = colors.outline,
                        uncheckedBorderColor = colors.outline,
                    ),
                )
            }
            Text(
                "When off, the map uses no internet and shows only sample data.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

/** One group of settings on a brand surface card, with the content card radius ([CardShape]). */
@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

private fun unitLabel(system: UnitSystem): String = when (system) {
    UnitSystem.AUTO -> "Automatic"
    UnitSystem.METRIC -> "Metric"
    UnitSystem.IMPERIAL -> "Imperial"
}
