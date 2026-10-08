package earth.walkthru.aqsync.ui.device

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import earth.walkthru.aqsync.R
import earth.walkthru.aqsync.data.DeviceEvent
import earth.walkthru.aqsync.data.SensorLabel
import earth.walkthru.aqsync.data.protocol.PairMode
import earth.walkthru.aqsync.ui.components.CollectEvents
import earth.walkthru.aqsync.ui.device.cards.LanAccess
import earth.walkthru.aqsync.ui.device.cards.StickerScan
import earth.walkthru.aqsync.ui.permissions.AccessDialogs
import earth.walkthru.aqsync.ui.permissions.LanPermission
import earth.walkthru.aqsync.ui.permissions.rememberBluetoothAccess
import earth.walkthru.aqsync.ui.permissions.rememberLocalNetworkAccess
import earth.walkthru.aqsync.ui.permissions.rememberNotificationPermission
import earth.walkthru.aqsync.ui.settings.SettingsSheet

/**
 * Stateful entry for the Device screen: the ViewModel, one-off events, the companion-device and
 * barcode launchers, permissions and every confirmation dialog. [DeviceScreen] gets plain state.
 *
 * The reboot dialog here is the only call path to `rebootAndReconnect()`. Rotate token, forget
 * bonds and pairing mode `none` are confirmed here too.
 */
@Composable
fun DeviceRoute(
    address: String,
    onOpenToday: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenDebug: () -> Unit,
    onDisconnected: () -> Unit,
    viewModel: DeviceViewModel = hiltViewModel<DeviceViewModel, DeviceViewModel.Factory>(key = address, creationCallback = { it.create(address) }),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val sensorInstallation by viewModel.sensorInstallation.collectAsStateWithLifecycle()
    val sensorHistory by viewModel.sensorHistory.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var showSettings by remember { mutableStateOf(false) }
    var confirmReboot by remember { mutableStateOf(false) }
    var confirmRotate by remember { mutableStateOf(false) }
    var confirmBonds by remember { mutableStateOf(false) }
    var confirmNoPasskey by remember { mutableStateOf(false) }
    var stickerScan by remember { mutableStateOf<StickerScan?>(null) }
    val scanUnrecognized = stringResource(R.string.sensor_scan_unrecognized)
    val scanUnavailable = stringResource(R.string.sensor_scan_unavailable)
    val clockHint = stringResource(R.string.event_clock_on_today)

    CollectEvents(viewModel.events) { ev ->
        when (ev) {
            is DeviceEvent.Error -> snackbar.showSnackbar(ev.message)
            is DeviceEvent.Info -> snackbar.showSnackbar(ev.message)
            is DeviceEvent.ClockConfirmationRequired -> snackbar.showSnackbar(clockHint)
        }
    }
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
    // Bluetooth for Reconnect, local network for the Wi-Fi sync actions. Asked only from those taps.
    val bluetooth = rememberBluetoothAccess()
    val localNetwork = rememberLocalNetworkAccess()
    AccessDialogs(bluetooth)
    AccessDialogs(localNetwork)

    DeviceScreen(
        state = state,
        extras = DeviceExtras(
            sensorInstallation = sensorInstallation,
            sensorHistoryCount = sensorHistory.size,
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
                DeviceAction.OpenSettings -> showSettings = true
                DeviceAction.Disconnect -> { viewModel.disconnect(); onDisconnected() }
                DeviceAction.Reconnect -> bluetooth.run { viewModel.reconnect() }
                DeviceAction.RefreshConfig -> viewModel.refreshConfig()
                is DeviceAction.SetAutoSync -> notificationPermission.ensureThen { viewModel.setAutoSync(action.on) }
                DeviceAction.SetupCompanion -> notificationPermission.ensureThen { viewModel.setupCompanion() }
                DeviceAction.RemoveCompanion -> viewModel.removeCompanion()
                DeviceAction.ScanSticker -> GmsBarcodeScanning.getClient(context).startScan()
                    .addOnSuccessListener { barcode ->
                        val value = barcode.rawValue.orEmpty()
                        stickerScan = if (SensorLabel.parse(value) == null) {
                            StickerScan(null, scanUnrecognized, System.nanoTime())
                        } else {
                            StickerScan(value.trim(), null, System.nanoTime())
                        }
                    }
                    .addOnFailureListener { stickerScan = StickerScan(null, scanUnavailable, System.nanoTime()) }
                is DeviceAction.SaveSensor -> viewModel.setSensorIdentity(action.sticker, action.batchEstimate, action.source)
                is DeviceAction.SavePairing ->
                    if (action.mode == PairMode.NONE) confirmNoPasskey = true else viewModel.setPairing(action.mode, action.pin)
                DeviceAction.RequestReboot -> confirmReboot = true
                is DeviceAction.SetWifiOn -> viewModel.setWifiOn(action.on)
                DeviceAction.ScanWifi -> viewModel.scanWifi()
                DeviceAction.ClearWifiScan -> viewModel.clearScan()
                is DeviceAction.JoinWifi -> viewModel.joinWifi(action.ssid, action.psk, action.open)
                DeviceAction.RequestLanAccess -> localNetwork.run { }
                is DeviceAction.SetLanOn -> if (action.on) localNetwork.run { viewModel.setLanOn(true) } else viewModel.setLanOn(false)
                // The token is fetched over Bluetooth. Local network is asked for here because the
                // token is only useful with it, but a refusal does not block the fetch.
                DeviceAction.FetchToken -> localNetwork.run(onDeclined = { viewModel.fetchToken() }) { viewModel.fetchToken() }
                DeviceAction.RequestRotateToken -> confirmRotate = true
                DeviceAction.ReconnectOverBluetooth -> bluetooth.run { viewModel.reconnectOverBluetooth() }
                DeviceAction.RequestForgetBonds -> confirmBonds = true
            }
        },
    )

    if (showSettings) SettingsSheet(onDismiss = { showSettings = false })

    if (confirmReboot) {
        AlertDialog(
            onDismissRequest = { confirmReboot = false },
            icon = { Icon(Icons.Rounded.RestartAlt, contentDescription = null) },
            title = { Text(stringResource(R.string.reboot_title)) },
            text = { Text(stringResource(R.string.reboot_confirm)) },
            // The only path to REBOOT in the app. Single-fire. The flag is cleared before the call,
            // and the repository refuses a second call while the first is in flight or a sync runs.
            confirmButton = {
                TextButton(onClick = { if (confirmReboot) { confirmReboot = false; viewModel.reboot() } }) { Text(stringResource(R.string.reboot_button)) }
            },
            dismissButton = { TextButton(onClick = { confirmReboot = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (confirmRotate) {
        AlertDialog(
            onDismissRequest = { confirmRotate = false },
            title = { Text(stringResource(R.string.rotate_title)) },
            text = { Text(stringResource(R.string.rotate_confirm)) },
            confirmButton = { TextButton(onClick = { confirmRotate = false; viewModel.rotateToken() }) { Text(stringResource(R.string.rotate_button)) } },
            dismissButton = { TextButton(onClick = { confirmRotate = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (confirmBonds) {
        AlertDialog(
            onDismissRequest = { confirmBonds = false },
            title = { Text(stringResource(R.string.bonds_forget)) },
            text = { Text(stringResource(R.string.bonds_confirm)) },
            confirmButton = { TextButton(onClick = { confirmBonds = false; viewModel.clearBonds() }) { Text(stringResource(R.string.bonds_forget_button)) } },
            dismissButton = { TextButton(onClick = { confirmBonds = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (confirmNoPasskey) {
        AlertDialog(
            onDismissRequest = { confirmNoPasskey = false },
            title = { Text(stringResource(R.string.pairing_none_title)) },
            text = { Text(stringResource(R.string.pairing_none_confirm)) },
            confirmButton = {
                TextButton(onClick = { confirmNoPasskey = false; viewModel.setPairing(PairMode.NONE, null) }) { Text(stringResource(R.string.pairing_none_button)) }
            },
            dismissButton = { TextButton(onClick = { confirmNoPasskey = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
