package earth.walkthru.aqsync.ui.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Settings
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import earth.walkthru.aqsync.R
import earth.walkthru.aqsync.data.ble.ConnectionState
import earth.walkthru.aqsync.data.protocol.TemperatureSource
import earth.walkthru.aqsync.ui.components.ConnectionBanner
import earth.walkthru.aqsync.ui.components.DeviceBottomBar
import earth.walkthru.aqsync.ui.components.DeviceTab
import earth.walkthru.aqsync.ui.components.PillButton
import earth.walkthru.aqsync.ui.components.TransportChip
import earth.walkthru.aqsync.ui.device.cards.BatchEstimateCard
import earth.walkthru.aqsync.ui.device.cards.BoardCard
import earth.walkthru.aqsync.ui.device.cards.ClockCard
import earth.walkthru.aqsync.ui.device.cards.DeviceInfoCard
import earth.walkthru.aqsync.ui.device.cards.EnvironmentCard
import earth.walkthru.aqsync.ui.device.cards.MetricHeroCard
import earth.walkthru.aqsync.ui.device.cards.ParticlesCard
import earth.walkthru.aqsync.ui.device.cards.ReadingsCard
import earth.walkthru.aqsync.ui.device.cards.SmartSyncCard
import earth.walkthru.aqsync.ui.device.cards.StorageCard
import earth.walkthru.aqsync.ui.device.cards.TodaySoFarCard

/** Everything Today can ask for. The route maps each to a ViewModel call, a dialog or navigation. */
sealed interface TodayAction {
    data object OpenHistory : TodayAction
    data object OpenFiles : TodayAction
    data object OpenDevice : TodayAction
    data object OpenDebug : TodayAction
    data object OpenDevices : TodayAction
    data object OpenSettings : TodayAction
    data object Disconnect : TodayAction
    data object Reconnect : TodayAction
    data object SmartSync : TodayAction
    data object RequestSetTime : TodayAction
    data object RequestFlush : TodayAction
}

/**
 * Today, stateless. Simple shows the hero reading, today so far, the sync state in words with one
 * primary action, and the clock. Detailed adds the other measurements, board, storage, batch
 * estimate and the device card. Developer adds identifiers and link internals on those cards.
 * Conditional items sit after the first item so a level change never moves the top of the list.
 */
@Composable
fun TodayScreen(
    state: TodayUiState,
    onAction: (TodayAction) -> Unit,
    modifier: Modifier = Modifier,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    val level = state.detailLevel
    val profile = state.capabilities.liveProfile
    val live = state.live?.sample
    val receivedAt = state.live?.receivedAtEpochMs
    // Hoisted so the scroll position belongs to the screen, not to whichever LazyColumn node
    // happens to be composed. A "Set time" tap must never reset it (see the keyed items below).
    val listState = rememberLazyListState()

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = {
                    Column {
                        Text(state.name ?: stringResource(R.string.device_unnamed), style = MaterialTheme.typography.titleLarge)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TransportChip(state.transport)
                            if (level.developer) {
                                state.info?.station?.let {
                                    Text(
                                        stringResource(R.string.today_station_short, it.take(8)),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                },
                actions = {
                    TextButton(onClick = { onAction(TodayAction.OpenDevices) }) { Text(stringResource(R.string.nav_devices)) }
                    if (level.developer) {
                        IconButton(onClick = { onAction(TodayAction.OpenDebug) }) { Icon(Icons.Rounded.BugReport, contentDescription = stringResource(R.string.nav_debug)) }
                    }
                    IconButton(onClick = { onAction(TodayAction.OpenSettings) }) { Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.nav_settings)) }
                    IconButton(onClick = { onAction(TodayAction.Disconnect) }) { Icon(Icons.Rounded.LinkOff, contentDescription = stringResource(R.string.nav_disconnect)) }
                },
            )
        },
        bottomBar = {
            DeviceBottomBar(DeviceTab.TODAY) {
                when (it) {
                    DeviceTab.HISTORY -> onAction(TodayAction.OpenHistory)
                    DeviceTab.FILES -> onAction(TodayAction.OpenFiles)
                    DeviceTab.DEVICE -> onAction(TodayAction.OpenDevice)
                    DeviceTab.TODAY -> Unit
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            // The banner lives outside the list. When it appears or disappears no list index shifts,
            // so the scroll position cannot jump.
            if (state.connection !is ConnectionState.Ready) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    ConnectionBanner(state.connection, reconnecting = state.reconnecting, technical = level.developer)
                    if (state.reconnecting == null && (state.connection is ConnectionState.Failed || state.connection is ConnectionState.Disconnected)) {
                        Spacer(Modifier.height(8.dp))
                        PillButton(stringResource(R.string.action_reconnect), onClick = { onAction(TodayAction.Reconnect) }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item(key = "now") {
                    if (state.showsParticles) {
                        MetricHeroCard(live, receivedAt, level)
                    } else {
                        EnvironmentCard(profile, live, receivedAt, hero = true)
                    }
                }
                item(key = "today") {
                    TodaySoFarCard(state.todaySoFar, clockMissing = state.status?.status?.utcSynced == false, level = level)
                }
                if (state.showsParticles && profile?.temperature == TemperatureSource.AMBIENT) {
                    item(key = "environment") { EnvironmentCard(profile, live, receivedAt, hero = false) }
                }
                item(key = "smart-sync") {
                    SmartSyncCard(
                        sync = state.sync,
                        archive = state.archiveTime,
                        enabled = state.connection.isReady,
                        level = level,
                        onSync = { onAction(TodayAction.SmartSync) },
                    )
                }
                item(key = "clock") {
                    ClockCard(
                        status = state.status?.status,
                        live = live,
                        enabled = state.connection.isReady,
                        level = level,
                        onSetTime = { onAction(TodayAction.RequestSetTime) },
                        onFlush = { onAction(TodayAction.RequestFlush) },
                    )
                }
                if (level.detailed) {
                    if (state.showsBatchEstimate) {
                        item(key = "batch-estimate") { BatchEstimateCard(live, receivedAt, state.config) }
                    }
                    if (state.showsParticles) {
                        item(key = "readings") { ReadingsCard(live, receivedAt, level) }
                        item(key = "particles") { ParticlesCard(live) }
                    }
                    item(key = "board") { BoardCard(live, profile, level) }
                    item(key = "storage") { StorageCard(state.status?.status, state.status?.receivedAtEpochMs, level) }
                    if (state.info != null) {
                        item(key = "info") {
                            DeviceInfoCard(
                                name = state.name,
                                info = state.info,
                                connection = state.connection,
                                transport = state.transport,
                                endpoint = null,
                                configReadAtEpochMs = null,
                                level = level,
                            )
                        }
                    }
                }
            }
        }
    }
}
