package earth.walkthru.aqsync.ui.files

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import earth.walkthru.aqsync.R
import earth.walkthru.aqsync.data.background.AutoSyncScheduler
import earth.walkthru.aqsync.data.ble.ConnectionState
import earth.walkthru.aqsync.ui.components.CollectEvents
import earth.walkthru.aqsync.ui.components.ConnectionBanner
import earth.walkthru.aqsync.ui.components.showScreenMessage
import earth.walkthru.aqsync.ui.permissions.rememberNotificationPermission
import earth.walkthru.opensensor.designsystem.AppIcons
import earth.walkthru.opensensor.files.DeviceFilesAction
import earth.walkthru.opensensor.files.FileMetricsScreen
import earth.walkthru.opensensor.files.FilesScreen
import earth.walkthru.opensensor.today.DeviceSectionTabs
import earth.walkthru.opensensor.today.DeviceTab
import earth.walkthru.opensensor.today.TransportChip

/**
 * The Android host of the shared Files screen (`:feature:files`). It adds what only Android has
 * here, the device session, background sync, the notification permission, the link banner and the
 * chart by file, through the shared screen's slots.
 */
@Composable
fun FilesRoute(
    address: String,
    onOpenToday: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenDevice: () -> Unit,
    onOpenFileMetrics: (station: String, name: String) -> Unit,
    onDisconnected: () -> Unit,
    viewModel: FilesViewModel = hiltViewModel<FilesViewModel, FilesViewModel.Factory>(key = address, creationCallback = { it.create(address) }),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val device by viewModel.deviceFiles.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val res = LocalResources.current
    val level = state.detailLevel

    // The sync runs under a dataSync foreground service whose notification needs POST_NOTIFICATIONS
    // on API 33+. Ask once, right before the first sync. Whatever the answer, the sync starts. A
    // denial only hides the notification.
    val notificationPermission = rememberNotificationPermission()

    CollectEvents(viewModel.events) { ev ->
        snackbar.showScreenMessage(res, ev)
    }

    FilesScreen(
        controller = viewModel.files,
        level = level,
        onOpenFileMetrics = onOpenFileMetrics,
        device = device,
        progress = viewModel.syncProgress,
        onDeviceAction = { action ->
            when (action) {
                DeviceFilesAction.Refresh -> viewModel.refresh()
                DeviceFilesAction.SyncAll -> viewModel.syncAll()
                is DeviceFilesAction.SyncOne -> viewModel.syncOne(action.name)
                DeviceFilesAction.CancelSync -> viewModel.cancelSync()
            }
        },
        guard = { action -> notificationPermission.ensureThen(action) },
        snackbar = snackbar,
        subtitleLead = { TransportChip(state.transport) },
        tabs = {
            DeviceSectionTabs(DeviceTab.FILES, containerColor = MaterialTheme.colorScheme.surface) {
                when (it) {
                    DeviceTab.TODAY -> onOpenToday()
                    DeviceTab.HISTORY -> onOpenHistory()
                    DeviceTab.DEVICE -> onOpenDevice()
                    DeviceTab.FILES -> Unit
                }
            }
        },
        banner = if (state.station.showsBanner) {
            { ConnectionBanner(state.connection, reconnecting = state.reconnecting, technical = level.developer, heldInBackground = state.heldInBackground) }
        } else {
            null
        },
        // From the archive alone, so it works without a session: one column per synced file.
        chart = {
            FileChartCard(
                state = state.fileChart,
                onToggle = viewModel::setFileChart,
                onMetric = viewModel::setFileChartMetric,
                onPage = viewModel::pageFileChart,
            )
        },
        idleHint = stringResource(R.string.files_connect_to_list),
        menuLead = { close ->
            // Background sync: a one-time WorkManager job (leave the app, watch the notification) and
            // the per-device Auto-sync switch, both also on the Device screen.
            DropdownMenuItem(
                text = { Text(stringResource(if (state.backgroundSyncActive) R.string.files_bg_queued else R.string.files_bg_sync)) },
                leadingIcon = { Icon(AppIcons.CloudSync, contentDescription = null) },
                enabled = state.connection.isReady && !state.syncRunning && !state.backgroundSyncActive,
                onClick = {
                    close()
                    notificationPermission.ensureThen { viewModel.syncNowInBackground() }
                },
            )
            state.autoSync?.let { on ->
                DropdownMenuItem(
                    text = { Text(stringResource(if (on) R.string.files_autosync_on else R.string.files_autosync_off, AutoSyncScheduler.PERIOD_MINUTES.toInt())) },
                    leadingIcon = { Icon(if (on) AppIcons.SyncLock else AppIcons.Sync, contentDescription = null) },
                    trailingIcon = { Switch(checked = on, onCheckedChange = null) },
                    onClick = {
                        close()
                        notificationPermission.ensureThen { viewModel.setAutoSync(!on) }
                    },
                )
            }
            HorizontalDivider()
        },
        menuTail = { close ->
            DropdownMenuItem(
                text = { Text(stringResource(R.string.nav_disconnect)) },
                leadingIcon = { Icon(AppIcons.LinkOff, contentDescription = null) },
                onClick = {
                    close()
                    viewModel.disconnect()
                    onDisconnected()
                },
            )
        },
    )
}

/** The shared inspector of one archived file. Purely descriptive, nothing here changes the archive. */
@Composable
fun FileMetricsRoute(station: String, name: String, onBack: () -> Unit, viewModel: FileMetricsViewModel = hiltViewModel()) {
    LaunchedEffect(station, name) { viewModel.metrics.load(station, name) }
    val state by viewModel.metrics.state.collectAsStateWithLifecycle()
    val level by viewModel.detailLevel.collectAsStateWithLifecycle()
    FileMetricsScreen(state = state, level = level, name = name, onBack = onBack)
}
