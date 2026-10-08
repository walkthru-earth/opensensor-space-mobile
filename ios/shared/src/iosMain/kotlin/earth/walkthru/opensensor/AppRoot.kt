package earth.walkthru.opensensor

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import earth.walkthru.aqsync.publicfeed.platformH3Grid
import earth.walkthru.opensensor.discover.DiscoverController
import earth.walkthru.opensensor.discover.DiscoverIcons
import earth.walkthru.opensensor.discover.DiscoverScreen
import earth.walkthru.opensensor.discover.DiscoverStrings
import earth.walkthru.opensensor.discover.UserDefaultsDiscoverPrefs
import earth.walkthru.opensensor.discover.bundledSampleFeed
import platform.Foundation.NSDate
import platform.Foundation.NSTimeZone
import platform.Foundation.NSUserDefaults
import platform.Foundation.localTimeZone
import platform.Foundation.timeIntervalSince1970

private enum class Tab { DISCOVER, MY_DEVICES, SETTINGS }

private const val SHOW_PUBLIC_KEY = "discover.showPublicSensors"

/**
 * The iOS root: three tabs, Discover, My devices and Settings, as on Android. Discover is the start
 * tab when no device is paired ([startOnDiscover], from the app's saved device access).
 */
@Composable
internal fun AppRoot(startOnDiscover: Boolean, companion: @Composable () -> Unit) {
    val defaults = remember { NSUserDefaults.standardUserDefaults }
    var tab by rememberSaveable { mutableStateOf(if (startOnDiscover) Tab.DISCOVER else Tab.MY_DEVICES) }
    var showPublic by remember { mutableStateOf(defaults.objectForKey(SHOW_PUBLIC_KEY) == null || defaults.boolForKey(SHOW_PUBLIC_KEY)) }
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
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        Column(Modifier.fillMaxSize()) {
            Surface(Modifier.weight(1f)) {
                when (tab) {
                    Tab.DISCOVER -> DiscoverScreen(
                        controller = controller,
                        strings = DiscoverStrings.English,
                        networkAllowed = showPublic,
                        onAddSensor = { tab = Tab.MY_DEVICES },
                    )
                    Tab.MY_DEVICES -> companion()
                    Tab.SETTINGS -> IosSettings(showPublic) { on ->
                        showPublic = on
                        defaults.setBool(on, SHOW_PUBLIC_KEY)
                    }
                }
            }
            NavigationBar {
                NavigationBarItem(tab == Tab.DISCOVER, { tab = Tab.DISCOVER }, { Icon(DiscoverIcons.Map, null) }, label = { Text("Discover") })
                NavigationBarItem(tab == Tab.MY_DEVICES, { tab = Tab.MY_DEVICES }, { Icon(DiscoverIcons.Sensors, null) }, label = { Text("My devices") })
                NavigationBarItem(tab == Tab.SETTINGS, { tab = Tab.SETTINGS }, { Icon(DiscoverIcons.Settings, null) }, label = { Text("Settings") })
            }
        }
    }
}

/** The iOS settings so far: the one switch for Discover network use (AGENTS.md boundary). */
@Composable
private fun IosSettings(showPublic: Boolean, onShowPublic: (Boolean) -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Show public sensors", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Switch(checked = showPublic, onCheckedChange = onShowPublic)
        }
        Text(
            "When off, the map uses no internet and shows only sample data.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
