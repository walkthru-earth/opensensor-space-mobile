package earth.walkthru.aqsync.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import earth.walkthru.aqsync.ui.debug.DebugScreen
import earth.walkthru.aqsync.ui.device.DeviceScreen
import earth.walkthru.aqsync.ui.device.TodayScreen
import earth.walkthru.aqsync.ui.files.FilesScreen
import earth.walkthru.aqsync.ui.history.FileMetricsScreen
import earth.walkthru.aqsync.ui.history.HistoryScreen
import earth.walkthru.aqsync.ui.permissions.rememberLanPermission
import earth.walkthru.aqsync.ui.scan.ScanScreen

@Composable
fun MainNavigation(modifier: Modifier = Modifier) {
    val backStack = rememberNavBackStack(ScanKey)
    LanDiscoveryEffect()

    fun openTab(key: NavKey) {
        // Today / Files / Device behave as sibling tabs: replace rather than stack.
        val last = backStack.lastOrNull()
        if (last is TodayKey || last is HistoryKey || last is FilesKey || last is DeviceKey) backStack.removeLastOrNull()
        backStack.add(key)
    }

    fun toScan() = backStack.removeAll { it !is ScanKey }

    NavDisplay(
        modifier = modifier,
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryProvider = entryProvider {
            entry<ScanKey> {
                ScanScreen(onConnected = { address -> backStack.add(TodayKey(address)) })
            }
            entry<TodayKey> { key ->
                TodayScreen(
                    address = key.address,
                    onOpenHistory = { openTab(HistoryKey(key.address)) },
                    onOpenFiles = { openTab(FilesKey(key.address)) },
                    onOpenDevice = { openTab(DeviceKey(key.address)) },
                    onOpenDebug = { backStack.add(DebugKey(key.address)) },
                    onDisconnected = { toScan() },
                )
            }
            entry<HistoryKey> { key ->
                HistoryScreen(
                    address = key.address,
                    onOpenToday = { openTab(TodayKey(key.address)) },
                    onOpenFiles = { openTab(FilesKey(key.address)) },
                    onOpenDevice = { openTab(DeviceKey(key.address)) },
                )
            }
            entry<FilesKey> { key ->
                FilesScreen(
                    address = key.address,
                    onOpenToday = { openTab(TodayKey(key.address)) },
                    onOpenHistory = { openTab(HistoryKey(key.address)) },
                    onOpenDevice = { openTab(DeviceKey(key.address)) },
                    onOpenFileMetrics = { station, name -> backStack.add(FileMetricsKey(station, name)) },
                    onDisconnected = { toScan() },
                )
            }
            entry<FileMetricsKey> { key ->
                FileMetricsScreen(
                    station = key.station,
                    name = key.name,
                    onBack = { if (backStack.lastOrNull() is FileMetricsKey) backStack.removeLastOrNull() },
                )
            }
            entry<DeviceKey> { key ->
                DeviceScreen(
                    address = key.address,
                    onOpenToday = { openTab(TodayKey(key.address)) },
                    onOpenHistory = { openTab(HistoryKey(key.address)) },
                    onOpenFiles = { openTab(FilesKey(key.address)) },
                    onOpenDebug = { backStack.add(DebugKey(key.address)) },
                    onDisconnected = { toScan() },
                )
            }
            entry<DebugKey> { key ->
                DebugScreen(
                    address = key.address,
                    onBack = { if (backStack.lastOrNull() is DebugKey) backStack.removeLastOrNull() },
                    onDisconnected = { toScan() },
                )
            }
        },
    )
}

/**
 * Browse mDNS for `_aqsync._tcp` while the app is started and the local-network permission is
 * held (API 37+; always on below). Stopping on background keeps the radio and the permission
 * honest: discovery is only ever running while the user is looking at the app.
 */
@Composable
private fun LanDiscoveryEffect(viewModel: LanDiscoveryViewModel = hiltViewModel()) {
    val perm = rememberLanPermission()
    LifecycleStartEffect(perm.granted) {
        viewModel.setActive(perm.granted)
        onStopOrDispose { viewModel.setActive(false) }
    }
}
