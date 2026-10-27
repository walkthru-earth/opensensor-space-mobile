package space.opensensor.ui.setup

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.opensensor.device.DeviceConfigAction
import space.opensensor.device.LanAccess
import space.opensensor.device.onAction
import space.opensensor.setup.SetupScreen
import space.opensensor.ui.device.DeviceViewModel
import space.opensensor.ui.permissions.AccessDialogs
import space.opensensor.ui.permissions.LanPermission
import space.opensensor.ui.permissions.rememberBluetoothAccess
import space.opensensor.ui.permissions.rememberLocalNetworkAccess

/**
 * Android entry for the shared wizard. Bluetooth is asked for from Look for sensors and local network
 * from Turn on Wi-Fi sync, never up front. Once a sensor is picked, Location and Wi-Fi run on the
 * same [DeviceViewModel] controller the Device page uses for that address.
 */
@Composable
fun SetupRoute(
    resume: String?,
    onClose: () -> Unit,
    onOpenSensor: (String) -> Unit,
    viewModel: SetupViewModel = hiltViewModel<SetupViewModel, SetupViewModel.Factory>(key = "setup:$resume", creationCallback = { it.create(resume) }),
) {
    val controller = viewModel.controller
    val state by controller.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val bluetooth = rememberBluetoothAccess()
    val localNetwork = rememberLocalNetworkAccess()
    AccessDialogs(bluetooth)
    AccessDialogs(localNetwork)

    val address = state.selected?.id.takeIf { state.paired }
    val config = address?.let { a ->
        hiltViewModel<DeviceViewModel, DeviceViewModel.Factory>(key = a, creationCallback = { it.create(a) }).controller
    }

    SetupScreen(
        controller = controller,
        config = config,
        bluetooth = bluetooth,
        lanAccess = LanAccess(localNetwork.granted, requestable = LanPermission.name != null),
        snackbar = snackbar,
        onConfigAction = { a ->
            val c = config ?: return@SetupScreen
            when (a) {
                DeviceConfigAction.RequestLanAccess -> localNetwork.run { }
                is DeviceConfigAction.SetLanOn -> if (a.on) localNetwork.run { c.setLanOn(true) } else c.setLanOn(false)
                DeviceConfigAction.FetchToken -> localNetwork.run(onDeclined = { c.fetchToken() }) { c.fetchToken() }
                DeviceConfigAction.ReconnectOverBluetooth -> bluetooth.run { c.reconnectOverBluetooth() }
                else -> c.onAction(a)
            }
        },
        onClose = onClose,
        onOpenSensor = onOpenSensor,
    )
}
