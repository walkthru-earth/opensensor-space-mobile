package space.opensensor.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import space.opensensor.shell.AppShell
import space.opensensor.shell.DebugKey
import space.opensensor.shell.DeviceKey
import space.opensensor.shell.DiscoverKey
import space.opensensor.shell.FileMetricsKey
import space.opensensor.shell.FilesKey
import space.opensensor.shell.HistoryKey
import space.opensensor.shell.ScanKey
import space.opensensor.shell.SettingsKey
import space.opensensor.shell.TabNavigator
import space.opensensor.shell.TodayKey
import space.opensensor.ui.debug.DebugScreen
import space.opensensor.ui.device.DeviceRoute
import space.opensensor.ui.device.TodayRoute
import space.opensensor.ui.discover.DiscoverRoute
import space.opensensor.ui.files.FileMetricsRoute
import space.opensensor.ui.files.FilesRoute
import space.opensensor.ui.history.HistoryScreen
import space.opensensor.ui.permissions.rememberLanGranted
import space.opensensor.ui.scan.ScanScreen
import space.opensensor.ui.settings.SettingsScreen

/**
 * The Android host of the shared shell ([AppShell], `:feature:shell`). The shell owns the bottom
 * bar, the per-tab back stacks and the Back rules on both apps. This file supplies the Android
 * screens as entries, each with its Hilt ViewModels, until a screen moves to common.
 */
@Composable
fun MainNavigation(modifier: Modifier = Modifier, level: ShellLevelViewModel = hiltViewModel()) {
    LanDiscoveryEffect()
    // Read at tap time, so a level changed in Settings applies without recomposing the entries.
    val developer = { level.developer.value == true }
    AppShell(entries = { nav -> androidEntries(nav, developer) }, modifier = modifier)
}

/** Every Android destination. Hilt stays at this edge, the shell never sees it. */
private fun EntryProviderScope<NavKey>.androidEntries(nav: TabNavigator, developer: () -> Boolean) {
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
            // The shell checks the stored level itself, and the Debug screen pops when the level drops.
            onOpenDebug = { nav.openDebug(key.address, developer = developer()) },
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
        FilesRoute(
            address = key.address,
            onOpenToday = { nav.openSection(TodayKey(key.address)) },
            onOpenHistory = { nav.openSection(HistoryKey(key.address)) },
            onOpenDevice = { nav.openSection(DeviceKey(key.address)) },
            onOpenFileMetrics = { station, name -> nav.push(FileMetricsKey(station, name)) },
            onDisconnected = { nav.toMyDevicesRoot() },
        )
    }
    entry<FileMetricsKey> { key ->
        FileMetricsRoute(
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

/**
 * Browse mDNS for `_ossync._tcp` while the app is started and the local-network permission is
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
