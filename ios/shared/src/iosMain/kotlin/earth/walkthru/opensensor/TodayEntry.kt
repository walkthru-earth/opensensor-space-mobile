package earth.walkthru.opensensor

import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import earth.walkthru.aqsync.settings.SettingsRepository
import earth.walkthru.aqsync.units.UnitFormatter
import earth.walkthru.aqsync.units.UnitSystem
import earth.walkthru.opensensor.designsystem.AppIcons
import earth.walkthru.opensensor.designsystem.FloatingIconButton
import earth.walkthru.opensensor.permissions.openAppSettings
import earth.walkthru.opensensor.shell.ScanKey
import earth.walkthru.opensensor.shell.TabNavigator
import earth.walkthru.opensensor.shell.TodayKey
import earth.walkthru.opensensor.shell.TopTab
import earth.walkthru.opensensor.today.TodayAction
import earth.walkthru.opensensor.today.TodayController
import earth.walkthru.opensensor.today.TodayScreen
import earth.walkthru.opensensor.today.todaySoFar
import earth.walkthru.opensensor.today.todayTitle
import kotlinx.coroutines.flow.drop

/** The companion state and the Swift verbs Today uses, made once per window. */
internal class CompanionToday(val state: CompanionState, val actions: CompanionLinkActions)

/**
 * Holds the Today controller for one Today entry, so it survives recomposition and is cleared when
 * the entry leaves the back stack. Today so far reads the hourly tier of the phone archive, the
 * same reader History uses.
 */
private class TodayHolder(today: CompanionToday, address: String, settings: SettingsRepository) : ViewModel() {
    private val station = snapshotFlow { today.state.info?.station }
    val controller = TodayController(
        scope = viewModelScope,
        source = CompanionTodaySource(
            scope = viewModelScope,
            state = today.state,
            address = address,
            archive = IosArchive.index.observeAll(),
            todaySoFar = todaySoFar(station, IosArchive.reader::observeHourlyDay),
            actions = today.actions,
        ),
        detailLevel = settings.detailLevel,
        clockSyncMode = settings.clockSyncMode,
    )
}

/**
 * Today of the connected sensor (`:feature:today`), the screen Android shows. Files, Device and Debug
 * are not on iOS yet, so those taps stay here. History opens the phone archive.
 */
@Composable
internal fun TodayEntry(address: String, today: CompanionToday, settings: SettingsRepository, units: UnitSystem, nav: TabNavigator, onOpenHistory: () -> Unit) {
    val controller = viewModel { TodayHolder(today, address, settings) }.controller
    val state by controller.state.collectAsState()
    // Another sensor was picked: this Today closes, as on Android.
    val current = state.snapshot.address
    LaunchedEffect(current) {
        if (current != null && current != address) nav.pop()
    }
    TodayScreen(
        controller = controller,
        units = remember(units) { UnitFormatter(units) },
        onAction = { action ->
            when (action) {
                TodayAction.OpenHistory -> onOpenHistory()
                TodayAction.OpenFiles, TodayAction.OpenDevice, TodayAction.OpenDebug -> Unit
                TodayAction.OpenDevices -> nav.pop()
                TodayAction.OpenSettings -> nav.select(TopTab.SETTINGS)
                TodayAction.OpenBluetoothSettings -> openAppSettings()
                TodayAction.Disconnect -> {
                    controller.disconnect()
                    nav.pop()
                }
                TodayAction.Reconnect -> controller.reconnect()
                TodayAction.SmartSync -> controller.smartSync()
            }
        },
    )
}

/** Opens Today when a sensor connects while My devices is on top, as Android opens it after connecting. */
@Composable
internal fun OpenTodayOnConnect(state: CompanionState, nav: TabNavigator) {
    LaunchedEffect(state, nav) {
        // The first value is the state on arrival. Coming back from Today must not open it again.
        snapshotFlow { state.selectedId?.takeIf { state.connected } }.drop(1).collect { id ->
            if (id != null && nav.top == ScanKey) nav.push(TodayKey(id))
        }
    }
}

/** A compact Today button over My devices while a sensor is connected. */
@Composable
internal fun TodayShortcut(state: CompanionState, nav: TabNavigator, modifier: Modifier = Modifier) {
    val id = state.selectedId?.takeIf { state.connected } ?: return
    val title = todayTitle()
    FloatingIconButton(onClick = { nav.push(TodayKey(id)) }, contentDescription = title, modifier = modifier) {
        Icon(AppIcons.Outlined.Sensors, contentDescription = null)
    }
}
