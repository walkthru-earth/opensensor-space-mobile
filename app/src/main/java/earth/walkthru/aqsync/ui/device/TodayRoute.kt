package earth.walkthru.aqsync.ui.device

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Storage
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import earth.walkthru.aqsync.R
import earth.walkthru.aqsync.data.sync.ClockQuality
import earth.walkthru.aqsync.ui.components.CollectEvents
import earth.walkthru.aqsync.ui.components.reasonRes
import earth.walkthru.aqsync.ui.components.showDeviceEvent
import earth.walkthru.aqsync.ui.permissions.AccessDialogs
import earth.walkthru.aqsync.ui.permissions.rememberBluetoothAccess
import earth.walkthru.aqsync.ui.permissions.rememberNotificationPermission
import earth.walkthru.aqsync.ui.settings.SettingsSheet
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Stateful entry for Today: owns the ViewModel, one-off events, confirmation dialogs and the
 * settings sheet, and hands [TodayScreen] plain state. Every device-changing action here
 * (set time, finalize, Smart Sync's clock update) is confirmed first.
 */
@Composable
fun TodayRoute(
    address: String,
    onOpenHistory: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenDevice: () -> Unit,
    onOpenDebug: () -> Unit,
    onDisconnected: () -> Unit,
    viewModel: TodayViewModel = hiltViewModel<TodayViewModel, TodayViewModel.Factory>(key = address, creationCallback = { it.create(address) }),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showSettings by remember { mutableStateOf(false) }
    var confirmTime by remember { mutableStateOf(false) }
    var confirmSmartSyncClock by remember { mutableStateOf<ClockQuality?>(null) }
    val res = LocalResources.current
    var confirmFlush by remember { mutableStateOf(false) }
    // Asked only from a tap: Reconnect needs Bluetooth, Sync shows a progress notification.
    val bluetooth = rememberBluetoothAccess()
    AccessDialogs(bluetooth)
    val notificationPermission = rememberNotificationPermission()

    CollectEvents(viewModel.events) { ev ->
        snackbar.showDeviceEvent(res, ev) { confirmSmartSyncClock = it }
    }
    // The repository dropped the session (user disconnected, or another device was picked).
    LaunchedEffect(state.address) {
        if (state.address != null && state.address != address) onDisconnected()
    }

    TodayScreen(
        state = state,
        snackbar = snackbar,
        onAction = { action ->
            when (action) {
                TodayAction.OpenHistory -> onOpenHistory()
                TodayAction.OpenFiles -> onOpenFiles()
                TodayAction.OpenDevice -> onOpenDevice()
                TodayAction.OpenDebug -> onOpenDebug()
                TodayAction.OpenDevices -> onDisconnected()
                TodayAction.OpenSettings -> showSettings = true
                TodayAction.Disconnect -> { viewModel.disconnect(); onDisconnected() }
                TodayAction.Reconnect -> bluetooth.run { viewModel.reconnect() }
                TodayAction.SmartSync -> notificationPermission.ensureThen { viewModel.smartSync() }
                TodayAction.RequestSetTime -> confirmTime = true
                TodayAction.RequestFlush -> confirmFlush = true
            }
        },
    )

    if (showSettings) SettingsSheet(onDismiss = { showSettings = false })

    if (confirmTime) {
        AlertDialog(
            onDismissRequest = { confirmTime = false },
            icon = { Icon(Icons.Rounded.Schedule, contentDescription = null) },
            title = { Text(stringResource(R.string.set_time)) },
            text = { Text(stringResource(R.string.set_time_confirm, phoneClock())) },
            confirmButton = { TextButton(onClick = { confirmTime = false; viewModel.setTimeFromPhone() }) { Text(stringResource(R.string.set_time_button)) } },
            dismissButton = { TextButton(onClick = { confirmTime = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    confirmSmartSyncClock?.let { reason ->
        AlertDialog(
            onDismissRequest = { confirmSmartSyncClock = null },
            icon = { Icon(Icons.Rounded.Schedule, contentDescription = null) },
            title = { Text(stringResource(R.string.sync_clock_title)) },
            text = { Text(stringResource(R.string.sync_clock_confirm, stringResource(reason.reasonRes()))) },
            confirmButton = {
                TextButton(onClick = { confirmSmartSyncClock = null; viewModel.smartSync(clockConfirmed = true) }) { Text(stringResource(R.string.sync_clock_button)) }
            },
            dismissButton = { TextButton(onClick = { confirmSmartSyncClock = null }) { Text(stringResource(R.string.action_not_now)) } },
        )
    }
    if (confirmFlush) {
        AlertDialog(
            onDismissRequest = { confirmFlush = false },
            icon = { Icon(Icons.Rounded.Storage, contentDescription = null) },
            title = { Text(stringResource(R.string.flush)) },
            text = { Text(stringResource(R.string.flush_confirm)) },
            confirmButton = { TextButton(onClick = { confirmFlush = false; viewModel.flush() }) { Text(stringResource(R.string.flush_button)) } },
            dismissButton = { TextButton(onClick = { confirmFlush = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

private fun phoneClock(): String =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z", Locale.US).withZone(ZoneId.systemDefault()).format(Instant.now())
