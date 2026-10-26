package space.opensensor.ui.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import space.opensensor.app.R
import space.opensensor.designsystem.AppIcons
import space.opensensor.device.DeviceConfigAction
import space.opensensor.device.DeviceConfigUiState
import space.opensensor.device.LanAccess
import space.opensensor.device.StickerScan
import space.opensensor.device.deviceConfigItems
import space.opensensor.device.sensorIdentityItem
import space.opensensor.today.DeviceSectionTabs
import space.opensensor.today.DeviceTab
import space.opensensor.today.TransportChip
import space.opensensor.ui.components.ConnectionBanner
import space.opensensor.ui.device.cards.AutoSyncCard
import space.opensensor.ui.device.cards.DeviceInfoCard

/** Everything the Device screen can ask for. Device-changing ones are confirmed by the shared controller. */
sealed interface DeviceAction {
    data object OpenToday : DeviceAction
    data object OpenHistory : DeviceAction
    data object OpenFiles : DeviceAction
    data object Disconnect : DeviceAction
    data object Reconnect : DeviceAction
    data object RefreshConfig : DeviceAction
    data class SetAutoSync(val on: Boolean) : DeviceAction
    data object SetupCompanion : DeviceAction
    data object RemoveCompanion : DeviceAction

    /** Pairing, Wi-Fi, Sync over Wi-Fi, bonds and the sticker card, from the shared Device section. */
    data class Config(val action: DeviceConfigAction) : DeviceAction
}

/** What the Device screen shows besides [DeviceUiState]: the last sticker scan and platform permission state. */
data class DeviceExtras(
    val stickerScan: StickerScan? = null,
    val lanAccess: LanAccess = LanAccess(granted = true, requestable = false),
)

/**
 * Device configuration, stateless: device header, Auto-sync, the fitted sensor (where the device
 * has one), Bluetooth pairing, Wi-Fi, LAN and bonds. Every write is one validated `SET_CONFIG`.
 * Simple shows the device, Auto-sync, Wi-Fi and Sync over Wi-Fi as state in words with their
 * actions. Detailed adds the sensor, pairing and paired phones cards. Explanations sit behind each
 * card's info button at every level.
 */
@Composable
fun DeviceScreen(
    state: DeviceUiState,
    config: DeviceConfigUiState,
    extras: DeviceExtras,
    onAction: (DeviceAction) -> Unit,
    modifier: Modifier = Modifier,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    val listState = rememberLazyListState()
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                    title = {
                        Column {
                            Text(stringResource(R.string.device_screen_title), style = MaterialTheme.typography.titleLarge)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (state.detailLevel.detailed) TransportChip(state.transport)
                                Text(state.name ?: stringResource(R.string.device_unnamed), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { onAction(DeviceAction.RefreshConfig) }, enabled = state.ready && state.isV2) {
                            Icon(AppIcons.Refresh, contentDescription = stringResource(R.string.device_refresh))
                        }
                        // Reconnect and Disconnect live only here, in the thing header, as on Today.
                        if (state.thing.offersReconnect) {
                            TextButton(onClick = { onAction(DeviceAction.Reconnect) }) { Text(stringResource(R.string.action_reconnect)) }
                        } else {
                            IconButton(onClick = { onAction(DeviceAction.Disconnect) }) { Icon(AppIcons.LinkOff, contentDescription = stringResource(R.string.nav_disconnect)) }
                        }
                    },
                )
                DeviceSectionTabs(DeviceTab.DEVICE) {
                    when (it) {
                        DeviceTab.TODAY -> onAction(DeviceAction.OpenToday)
                        DeviceTab.HISTORY -> onAction(DeviceAction.OpenHistory)
                        DeviceTab.FILES -> onAction(DeviceAction.OpenFiles)
                        DeviceTab.DEVICE -> Unit
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            if (state.thing.showsBanner) {
                Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    ConnectionBanner(state.connection, technical = state.detailLevel.developer, heldInBackground = state.heldInBackground)
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item(key = "info") {
                    DeviceInfoCard(
                        name = state.name,
                        info = state.info,
                        connection = state.connection,
                        transport = state.transport,
                        endpoint = state.endpoint,
                        configReadAtEpochMs = state.config?.atEpochMs,
                        level = state.detailLevel,
                    )
                }
                // Device configuration beyond Auto-sync and Wi-Fi is Detailed. Simple still shows the
                // pairing card when it needs attention (default PIN or a pending restart).
                sensorIdentityItem(config, extras.stickerScan, canScan = true) { onAction(DeviceAction.Config(it)) }
                item(key = "autosync") {
                    AutoSyncCard(
                        state,
                        onToggle = { onAction(DeviceAction.SetAutoSync(it)) },
                        onSetupCompanion = { onAction(DeviceAction.SetupCompanion) },
                        onRemoveCompanion = { onAction(DeviceAction.RemoveCompanion) },
                    )
                }
                deviceConfigItems(config, extras.lanAccess) { onAction(DeviceAction.Config(it)) }
            }
        }
    }
}
