package earth.walkthru.aqsync.ui.device

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import earth.walkthru.aqsync.R
import earth.walkthru.aqsync.ui.components.CollectEvents
import earth.walkthru.aqsync.ui.components.showScreenMessage
import earth.walkthru.aqsync.ui.permissions.AccessDialogs
import earth.walkthru.aqsync.ui.permissions.LanPermission
import earth.walkthru.aqsync.ui.permissions.rememberBluetoothAccess
import earth.walkthru.aqsync.ui.permissions.rememberLocalNetworkAccess
import earth.walkthru.aqsync.ui.permissions.rememberNotificationPermission
import earth.walkthru.opensensor.device.DeviceConfigAction
import earth.walkthru.opensensor.device.DeviceConfigEffects
import earth.walkthru.opensensor.device.LanAccess
import earth.walkthru.opensensor.device.StickerScan
import earth.walkthru.opensensor.device.StickerScans
import earth.walkthru.opensensor.device.onAction

/**
 * Stateful entry for the Device screen: the ViewModel, one-off events, the companion-device and
 * barcode launchers and permissions. [DeviceScreen] gets plain state.
 *
 * The four confirmations (restart, replace token, forget paired phones, pairing mode `none`) are
 * the shared [DeviceConfigEffects] dialogs over the ViewModel's controller, the same ones iOS shows.
 * Its reboot confirm is the only call path to `rebootAndReconnect()`.
 */
@Composable
fun DeviceRoute(
    address: String,
    onOpenToday: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenDebug: () -> Unit,
    onOpenSettings: () -> Unit,
    onDisconnected: () -> Unit,
    viewModel: DeviceViewModel = hiltViewModel<DeviceViewModel, DeviceViewModel.Factory>(key = address, creationCallback = { it.create(address) }),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val controller = viewModel.controller
    val config by controller.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var stickerScan by remember { mutableStateOf<StickerScan?>(null) }
    val scanUnrecognized = stringResource(R.string.sensor_scan_unrecognized)
    val scanUnavailable = stringResource(R.string.sensor_scan_unavailable)
    val clockHint = stringResource(R.string.event_clock_on_today)
    val res = LocalResources.current

    CollectEvents(viewModel.events) { ev ->
        snackbar.showScreenMessage(res, ev) { snackbar.showSnackbar(clockHint) }
    }
    // Confirm dialogs and the controller's own notices (validation, sensor saved).
    DeviceConfigEffects(controller, snackbar)
    // The companion-device association is a system dialog launched from an IntentSender.
    val companionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        viewModel.onCompanionResult(result.resultCode == Activity.RESULT_OK)
    }
    CollectEvents(viewModel.launchRequests) { sender -> companionLauncher.launch(IntentSenderRequest.Builder(sender).build()) }
    LaunchedEffect(state.address) {
        if (state.address != null && state.address != address) onDisconnected()
    }

    // The worker's progress and summary notifications need POST_NOTIFICATIONS on API 33+. Ask once
    // when Auto-sync is switched on (same helper as the first Sync tap). A denial still enables it.
    val notificationPermission = rememberNotificationPermission()
    // Reconnect uses an enrolled LAN target if Bluetooth is unavailable; Wi-Fi actions ask for LAN access.
    val bluetooth = rememberBluetoothAccess()
    val localNetwork = rememberLocalNetworkAccess()
    val lanReconnectAvailable by viewModel.lanReconnectAvailable.collectAsStateWithLifecycle()
    AccessDialogs(bluetooth)
    AccessDialogs(localNetwork)

    DeviceScreen(
        state = state,
        config = config,
        extras = DeviceExtras(
            stickerScan = stickerScan,
            lanAccess = LanAccess(localNetwork.granted, requestable = LanPermission.name != null),
        ),
        snackbar = snackbar,
        onAction = { action ->
            when (action) {
                DeviceAction.OpenToday -> onOpenToday()
                DeviceAction.OpenHistory -> onOpenHistory()
                DeviceAction.OpenFiles -> onOpenFiles()
                DeviceAction.OpenDebug -> onOpenDebug()
                DeviceAction.OpenSettings -> onOpenSettings()
                DeviceAction.Disconnect -> { viewModel.disconnect(); onDisconnected() }
                DeviceAction.Reconnect -> {
                    if (canReconnectLanOnly(bluetooth.ready, localNetwork.ready, lanReconnectAvailable)) {
                        viewModel.reconnectLanOnly()
                    } else {
                        bluetooth.run { viewModel.reconnect() }
                    }
                }
                DeviceAction.RefreshConfig -> controller.refresh()
                is DeviceAction.SetAutoSync -> notificationPermission.ensureThen { viewModel.setAutoSync(action.on) }
                DeviceAction.SetupCompanion -> notificationPermission.ensureThen { viewModel.setupCompanion() }
                DeviceAction.RemoveCompanion -> viewModel.removeCompanion()
                is DeviceAction.Config -> when (val a = action.action) {
                    DeviceConfigAction.ScanSticker -> GmsBarcodeScanning.getClient(context).startScan()
                        // The same reading as iOS (StickerScans). A cancel has no listener and leaves the card as it was.
                        .addOnSuccessListener { barcode -> stickerScan = StickerScans.read(stickerScan, barcode.rawValue.orEmpty(), scanUnrecognized) }
                        .addOnFailureListener { stickerScan = StickerScans.failed(stickerScan, scanUnavailable) }
                    DeviceConfigAction.RequestLanAccess -> localNetwork.run { }
                    is DeviceConfigAction.SetLanOn -> if (a.on) localNetwork.run { controller.setLanOn(true) } else controller.setLanOn(false)
                    // The token is fetched over Bluetooth. Local network is asked for here because the
                    // token is only useful with it, but a refusal does not block the fetch.
                    DeviceConfigAction.FetchToken -> localNetwork.run(onDeclined = { controller.fetchToken() }) { controller.fetchToken() }
                    DeviceConfigAction.ReconnectOverBluetooth -> bluetooth.run { controller.reconnectOverBluetooth() }
                    else -> controller.onAction(a)
                }
            }
        },
    )
}
