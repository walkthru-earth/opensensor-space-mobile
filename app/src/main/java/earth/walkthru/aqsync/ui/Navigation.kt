package earth.walkthru.aqsync.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import dagger.hilt.android.lifecycle.HiltViewModel
import earth.walkthru.aqsync.data.local.database.KnownDeviceDao
import earth.walkthru.aqsync.ui.debug.DebugScreen
import earth.walkthru.aqsync.ui.device.DeviceRoute
import earth.walkthru.aqsync.ui.device.TodayRoute
import earth.walkthru.aqsync.ui.discover.DiscoverRoute
import earth.walkthru.aqsync.ui.files.FilesScreen
import earth.walkthru.aqsync.ui.history.FileMetricsScreen
import earth.walkthru.aqsync.ui.history.HistoryScreen
import earth.walkthru.aqsync.ui.permissions.rememberLanGranted
import earth.walkthru.aqsync.ui.scan.ScanScreen
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Chooses the start tab once. Discover when no device is known yet, My devices (the scan list)
 * once one is.
 */
@HiltViewModel
class StartViewModel @Inject constructor(knownDeviceDao: KnownDeviceDao) : ViewModel() {
    val startKey: StateFlow<NavKey?> = flow<NavKey?> {
        emit(if (knownDeviceDao.observeAll().first().isEmpty()) DiscoverKey else ScanKey)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
}

@Composable
fun MainNavigation(modifier: Modifier = Modifier, start: StartViewModel = hiltViewModel()) {
    val startKey by start.startKey.collectAsStateWithLifecycle()
    // A single Room read, a few milliseconds. Nothing is drawn until the start tab is known.
    startKey?.let { MainNavigation(it, modifier) }
}

@Composable
private fun MainNavigation(startKey: NavKey, modifier: Modifier) {
    val backStack = rememberNavBackStack(startKey)
    LanDiscoveryEffect()

    /** Discover and My devices are top-level. Return to one already on the stack, else open it. */
    fun toTopLevel(key: NavKey) {
        val at = backStack.indexOfLast { it == key }
        if (at >= 0) {
            while (backStack.size > at + 1) backStack.removeAt(backStack.lastIndex)
        } else {
            backStack.add(key)
        }
    }

    fun openTab(key: NavKey) {
        // Device tabs are siblings: replace rather than stack, and keep the active tab intact.
        val last = backStack.lastOrNull()
        if (last == key) return
        if (last is TodayKey || last is HistoryKey || last is FilesKey || last is DeviceKey) backStack.removeLastOrNull()
        backStack.add(key)
    }

    fun toScan() {
        if (backStack.none { it is ScanKey }) backStack.add(0, ScanKey)
        backStack.removeAll { it !is ScanKey && it !is DiscoverKey }
        toTopLevel(ScanKey)
    }

    NavDisplay(
        modifier = modifier,
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = entryProvider {
            entry<DiscoverKey> {
                DiscoverRoute(
                    onAddSensor = { toTopLevel(ScanKey) },
                    onMyDevices = { toTopLevel(ScanKey) },
                )
            }
            entry<ScanKey> {
                ScanScreen(
                    onConnected = { address -> backStack.add(TodayKey(address)) },
                    onOpenDiscover = { toTopLevel(DiscoverKey) },
                )
            }
            entry<TodayKey> { key ->
                TodayRoute(
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
                DeviceRoute(
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
