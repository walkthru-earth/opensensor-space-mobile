package earth.walkthru.opensensor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import earth.walkthru.aqsync.settings.SettingsRepository
import earth.walkthru.aqsync.units.UnitFormatter
import earth.walkthru.aqsync.units.UnitSystem
import earth.walkthru.opensensor.today.TodayAction
import earth.walkthru.opensensor.today.TodayController
import earth.walkthru.opensensor.today.TodayScreen
import earth.walkthru.opensensor.today.todaySoFar

/** The companion state and the Swift verbs Today uses, made once per window. */
internal class CompanionToday(val state: CompanionState, val actions: CompanionLinkActions)

/**
 * Holds the Today controller for one Today entry, so it survives recomposition and is cleared when
 * the entry leaves the back stack. Today so far reads the hourly tier of the phone archive, the
 * same reader History uses.
 */
private class TodayHolder(today: CompanionToday, address: String, settings: SettingsRepository) : ViewModel() {
    // The one link may hold another sensor. Its station must not fill this sensor's Today so far.
    private val station = snapshotFlow { today.state.info?.station?.takeIf { today.state.selectedId == address } }
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
 * Today of one Bluetooth sensor (`:feature:today`), the screen Android shows. Smart Sync, Reconnect
 * and Disconnect run here. Every other action goes to [onNavigate].
 */
@Composable
internal fun TodayEntry(address: String, today: CompanionToday, settings: SettingsRepository, units: UnitSystem, onNavigate: (TodayAction) -> Unit) {
    val controller = viewModel(key = "today-$address") { TodayHolder(today, address, settings) }.controller
    TodayScreen(
        controller = controller,
        units = remember(units) { UnitFormatter(units) },
        onAction = { action ->
            when (action) {
                TodayAction.SmartSync -> controller.smartSync()
                TodayAction.Reconnect -> controller.reconnect()
                // The sensor keeps recording. My devices shows it as known.
                TodayAction.Disconnect -> {
                    controller.disconnect()
                    onNavigate(TodayAction.OpenDevices)
                }
                else -> onNavigate(action)
            }
        },
    )
}
