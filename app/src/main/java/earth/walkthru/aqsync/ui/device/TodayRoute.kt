package earth.walkthru.aqsync.ui.device

import android.content.Intent
import android.provider.Settings
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import earth.walkthru.aqsync.data.lan.LanDevice
import earth.walkthru.aqsync.data.local.database.KnownDevice
import earth.walkthru.aqsync.ui.components.CollectEvents
import earth.walkthru.aqsync.ui.components.LocalUnits
import earth.walkthru.aqsync.ui.components.showDeviceEvent
import earth.walkthru.aqsync.ui.permissions.AccessDialogs
import earth.walkthru.aqsync.ui.permissions.rememberBluetoothAccess
import earth.walkthru.aqsync.ui.permissions.rememberLocalNetworkAccess
import earth.walkthru.aqsync.ui.permissions.rememberNotificationPermission
import earth.walkthru.opensensor.today.TodayAction
import earth.walkthru.opensensor.today.TodayScreen

/** A LAN-only intent cannot construct BLE; use it only for a reachable enrolled sensor. */
internal fun canReconnectLanOnly(bluetoothReady: Boolean, localNetworkReady: Boolean, enrolledLanSeen: Boolean): Boolean =
    !bluetoothReady && localNetworkReady && enrolledLanSeen

internal fun hasLanReconnectTarget(known: KnownDevice?, discovered: Map<String, LanDevice>): Boolean {
    val station = known?.station ?: return false
    return known.hasLanToken && discovered[station]?.isConnectable == true
}

/**
 * Android entry for the shared Today screen (`:feature:today`). The screen and its confirmation
 * dialogs are shared. This keeps Android's runtime permission asks before Reconnect and Smart Sync,
 * the Bluetooth settings intent, and the repository's own snackbar lines.
 */
@Composable
fun TodayRoute(
    address: String,
    onOpenHistory: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenDevice: () -> Unit,
    onOpenDebug: () -> Unit,
    onOpenSettings: () -> Unit,
    onDisconnected: () -> Unit,
    viewModel: TodayViewModel = hiltViewModel<TodayViewModel, TodayViewModel.Factory>(key = address, creationCallback = { it.create(address) }),
) {
    val controller = viewModel.controller
    val state by controller.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val res = LocalResources.current
    val context = LocalContext.current
    // Asked only from a tap. A previously enrolled sensor may reconnect over the discovered LAN.
    val bluetooth = rememberBluetoothAccess()
    val localNetwork = rememberLocalNetworkAccess()
    val lanReconnectAvailable by viewModel.lanReconnectAvailable.collectAsStateWithLifecycle()
    AccessDialogs(bluetooth)
    AccessDialogs(localNetwork)
    val notificationPermission = rememberNotificationPermission()

    // The clock question reaches the shared controller through the TodaySource notices.
    CollectEvents(viewModel.events) { ev -> snackbar.showDeviceEvent(res, ev) {} }
    // The repository dropped the session (user disconnected, or another device was picked).
    val current = state.snapshot.address
    LaunchedEffect(current) {
        if (current != null && current != address) onDisconnected()
    }

    TodayScreen(
        controller = controller,
        units = LocalUnits.current,
        snackbar = snackbar,
        onAction = { action ->
            when (action) {
                TodayAction.OpenHistory -> onOpenHistory()
                TodayAction.OpenFiles -> onOpenFiles()
                TodayAction.OpenDevice -> onOpenDevice()
                TodayAction.OpenDebug -> onOpenDebug()
                TodayAction.OpenDevices -> onDisconnected()
                TodayAction.OpenSettings -> onOpenSettings()
                TodayAction.OpenBluetoothSettings ->
                    context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                TodayAction.Disconnect -> {
                    controller.disconnect()
                    onDisconnected()
                }
                TodayAction.Reconnect -> {
                    if (canReconnectLanOnly(bluetooth.ready, localNetwork.ready, lanReconnectAvailable)) {
                        viewModel.reconnectLanOnly()
                    } else {
                        bluetooth.run { controller.reconnect() }
                    }
                }
                TodayAction.SmartSync -> notificationPermission.ensureThen { controller.smartSync() }
            }
        },
    )
}
