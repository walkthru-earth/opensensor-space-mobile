package earth.walkthru.aqsync.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import earth.walkthru.aqsync.R
import earth.walkthru.aqsync.ui.debug.DebugScreen
import earth.walkthru.aqsync.ui.device.DeviceRoute
import earth.walkthru.aqsync.ui.device.TodayRoute
import earth.walkthru.aqsync.ui.discover.DiscoverRoute
import earth.walkthru.aqsync.ui.files.FilesScreen
import earth.walkthru.aqsync.ui.history.FileMetricsScreen
import earth.walkthru.aqsync.ui.history.HistoryScreen
import earth.walkthru.aqsync.ui.permissions.rememberLanGranted
import earth.walkthru.aqsync.ui.scan.ScanScreen
import earth.walkthru.aqsync.ui.settings.SettingsScreen
import earth.walkthru.opensensor.designsystem.FloatingControl

/**
 * The app root: one bottom bar (Discover, My devices, Settings) around one [NavDisplay]. Discover
 * is always the start tab, with or without a paired device, and nothing is asked at launch.
 * Each tab keeps its own Navigation 3 back stack, decorated separately so a device left open in
 * My devices keeps its ViewModels while another tab is shown. The rules live in [TabNavigator].
 */
@Composable
fun MainNavigation(modifier: Modifier = Modifier) {
    val discover = rememberNavBackStack(DiscoverKey)
    val devices = rememberNavBackStack(ScanKey)
    val settings = rememberNavBackStack(SettingsKey)
    var tab by rememberSaveable { mutableStateOf(TopTab.DISCOVER) }
    val nav = remember(discover, devices, settings) {
        TabNavigator(
            stacks = mapOf(TopTab.DISCOVER to discover, TopTab.MY_DEVICES to devices, TopTab.SETTINGS to settings),
            readTab = { tab },
            writeTab = { tab = it },
        )
    }
    LanDiscoveryEffect()

    val provider = entryProvider<NavKey> {
        entry<DiscoverKey> {
            DiscoverRoute(onAddSensor = { nav.toMyDevicesRoot() })
        }
        entry<ScanKey> {
            ScanScreen(onConnected = { address -> nav.push(TodayKey(address)) })
        }
        entry<SettingsKey> {
            SettingsScreen()
        }
        entry<TodayKey> { key ->
            TodayRoute(
                address = key.address,
                onOpenHistory = { nav.openSection(HistoryKey(key.address)) },
                onOpenFiles = { nav.openSection(FilesKey(key.address)) },
                onOpenDevice = { nav.openSection(DeviceKey(key.address)) },
                onOpenDebug = { nav.push(DebugKey(key.address)) },
                onOpenSettings = { nav.select(TopTab.SETTINGS) },
                onDisconnected = { nav.toMyDevicesRoot() },
            )
        }
        entry<HistoryKey> { key ->
            HistoryScreen(
                address = key.address,
                onOpenToday = { nav.openSection(TodayKey(key.address)) },
                onOpenFiles = { nav.openSection(FilesKey(key.address)) },
                onOpenDevice = { nav.openSection(DeviceKey(key.address)) },
            )
        }
        entry<FilesKey> { key ->
            FilesScreen(
                address = key.address,
                onOpenToday = { nav.openSection(TodayKey(key.address)) },
                onOpenHistory = { nav.openSection(HistoryKey(key.address)) },
                onOpenDevice = { nav.openSection(DeviceKey(key.address)) },
                onOpenFileMetrics = { station, name -> nav.push(FileMetricsKey(station, name)) },
                onDisconnected = { nav.toMyDevicesRoot() },
            )
        }
        entry<FileMetricsKey> { key ->
            FileMetricsScreen(
                station = key.station,
                name = key.name,
                onBack = { nav.popIfTop { it is FileMetricsKey } },
            )
        }
        entry<DeviceKey> { key ->
            DeviceRoute(
                address = key.address,
                onOpenToday = { nav.openSection(TodayKey(key.address)) },
                onOpenHistory = { nav.openSection(HistoryKey(key.address)) },
                onOpenFiles = { nav.openSection(FilesKey(key.address)) },
                onOpenDebug = { nav.push(DebugKey(key.address)) },
                onOpenSettings = { nav.select(TopTab.SETTINGS) },
                onDisconnected = { nav.toMyDevicesRoot() },
            )
        }
        entry<DebugKey> { key ->
            DebugScreen(
                address = key.address,
                onBack = { nav.popIfTop { it is DebugKey } },
                onDisconnected = { nav.toMyDevicesRoot() },
            )
        }
    }

    // One decorated list per tab. A key leaves its stack only on a real pop, which is when its
    // saved state and ViewModels are cleared. Switching tabs only changes what is drawn.
    val entriesByTab = TopTab.entries.associateWith { t ->
        key(t) {
            rememberDecoratedNavEntries(
                backStack = nav.stack(t),
                entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
                entryProvider = provider,
            )
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        // Screens own their top insets. Only the bar's height is passed down, and consumed so a
        // screen's own Scaffold does not add the navigation bar inset a second time.
        contentWindowInsets = WindowInsets(0),
        bottomBar = { if (nav.showsBottomBar) AppBottomBar(nav.tab, nav::select) },
    ) { padding ->
        NavDisplay(
            entries = nav.visibleTabs.flatMap { entriesByTab.getValue(it) },
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
            onBack = { nav.pop() },
        )
    }
}

@Composable
private fun AppBottomBar(selected: TopTab, onSelect: (TopTab) -> Unit) {
    NavigationBar {
        TopTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                colors = FloatingControl.navigationItemColors(),
                icon = {
                    Icon(
                        when (tab) {
                            TopTab.DISCOVER -> Icons.Outlined.Map
                            TopTab.MY_DEVICES -> Icons.Outlined.Sensors
                            TopTab.SETTINGS -> Icons.Outlined.Settings
                        },
                        contentDescription = null,
                    )
                },
                label = {
                    Text(
                        stringResource(
                            when (tab) {
                                TopTab.DISCOVER -> R.string.tab_discover
                                TopTab.MY_DEVICES -> R.string.tab_my_devices
                                TopTab.SETTINGS -> R.string.settings_title
                            },
                        ),
                    )
                },
            )
        }
    }
}

/**
 * Browse mDNS for `_aqsync._tcp` while the app is started and the local-network permission is
 * already held (API 37+, always on below). It never asks. The Device screen asks from a Wi-Fi action. Stopping on background keeps the radio and the permission
 * honest: discovery is only ever running while the user is looking at the app.
 */
@Composable
private fun LanDiscoveryEffect(viewModel: LanDiscoveryViewModel = hiltViewModel()) {
    val granted = rememberLanGranted()
    LifecycleStartEffect(granted) {
        viewModel.setActive(granted)
        onStopOrDispose { viewModel.setActive(false) }
    }
}
