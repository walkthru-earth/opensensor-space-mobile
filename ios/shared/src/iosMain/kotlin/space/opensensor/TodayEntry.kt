package space.opensensor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import space.opensensor.settings.SettingsRepository
import space.opensensor.today.TodayAction
import space.opensensor.today.TodayController
import space.opensensor.today.TodayScreen
import space.opensensor.today.todaySoFar
import space.opensensor.units.UnitFormatter
import space.opensensor.units.UnitSystem

/** The companion state and the Swift verbs Today uses, made once per window. */
internal class CompanionToday(val state: CompanionState, val actions: CompanionLinkActions)

/**
 * Holds the Today controller for one Today entry, so it survives recomposition and is cleared when
 * the entry leaves the back stack. Today so far reads the hourly tier of the phone archive, the
 * same reader History uses.
 */
private class TodayHolder(today: CompanionToday, devices: DevicesBridge, address: String, settings: SettingsRepository) : ViewModel() {
    // The one link may hold another sensor. Its station must not fill this sensor's Today so far.
    private val station = snapshotFlow {
        val lan = today.state.connectedLanForStation(devices.stationFor(address))
        lan?.let(today.state.lanInfo::get)?.station ?: today.state.info?.station?.takeIf { today.state.selectedId == address }
    }
    val controller = TodayController(
        scope = viewModelScope,
        source = CompanionTodaySource(
            scope = viewModelScope,
            state = today.state,
            address = address,
            archive = IosArchive.index.observeAll(),
            todaySoFar = todaySoFar(station, IosArchive.reader::observeHourlyDay),
            actions = today.actions,
            stationFor = devices::stationFor,
            nameFor = devices::nameFor,
        ),
        detailLevel = settings.detailLevel,
    )
}

/**
 * Today of one Bluetooth sensor (`:feature:today`), the screen Android shows. Smart Sync, Reconnect
 * and Disconnect run here. Every other action goes to [onNavigate]. Reconnect is the tap that asks
 * for Bluetooth, since opening the sensor from My devices never does (#147).
 */
@Composable
internal fun TodayEntry(
    address: String,
    today: CompanionToday,
    devices: DevicesBridge,
    settings: SettingsRepository,
    units: UnitSystem,
    requestBluetooth: () -> Unit,
    onNavigate: (TodayAction) -> Unit,
) {
    val controller = viewModel(key = "today-$address") { TodayHolder(today, devices, address, settings) }.controller
    val bluetooth = rememberIosBluetoothAccess(devices.state, requestBluetooth)
    TodayScreen(
        controller = controller,
        units = remember(units) { UnitFormatter(units) },
        onAction = { action ->
            when (action) {
                TodayAction.SmartSync -> controller.smartSync()
                TodayAction.StopSync -> controller.stopSync()
                // A refusal still reconnects, over the Wi-Fi link when the sensor has one.
                TodayAction.Reconnect -> bluetooth.run(onDeclined = controller::reconnect, action = controller::reconnect)
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
