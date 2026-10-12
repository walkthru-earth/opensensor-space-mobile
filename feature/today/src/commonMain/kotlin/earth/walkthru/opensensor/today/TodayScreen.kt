package earth.walkthru.opensensor.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import earth.walkthru.aqsync.data.ble.ConnectionState
import earth.walkthru.aqsync.data.ble.TransportKind
import earth.walkthru.aqsync.units.UnitFormatter
import earth.walkthru.opensensor.designsystem.AppIcons
import earth.walkthru.opensensor.designsystem.PillButton
import earth.walkthru.opensensor.designsystem.SectionTabs
import earth.walkthru.opensensor.designsystem.StatusDot
import earth.walkthru.opensensor.designsystem.TagChip
import earth.walkthru.opensensor.today.resources.Res
import earth.walkthru.opensensor.today.resources.action_cancel
import earth.walkthru.opensensor.today.resources.action_reconnect
import earth.walkthru.opensensor.today.resources.conn_bluetooth_settings
import earth.walkthru.opensensor.today.resources.conn_link_lost
import earth.walkthru.opensensor.today.resources.conn_link_lost_detail
import earth.walkthru.opensensor.today.resources.device_unnamed
import earth.walkthru.opensensor.today.resources.flush
import earth.walkthru.opensensor.today.resources.flush_button
import earth.walkthru.opensensor.today.resources.flush_confirm
import earth.walkthru.opensensor.today.resources.msg_busy
import earth.walkthru.opensensor.today.resources.msg_clock_set
import earth.walkthru.opensensor.today.resources.msg_failed_flush
import earth.walkthru.opensensor.today.resources.msg_failed_set_time
import earth.walkthru.opensensor.today.resources.msg_finalized
import earth.walkthru.opensensor.today.resources.msg_rejected_flush
import earth.walkthru.opensensor.today.resources.msg_rejected_set_time
import earth.walkthru.opensensor.today.resources.nav_debug
import earth.walkthru.opensensor.today.resources.nav_devices
import earth.walkthru.opensensor.today.resources.nav_disconnect
import earth.walkthru.opensensor.today.resources.nav_settings
import earth.walkthru.opensensor.today.resources.tab_device
import earth.walkthru.opensensor.today.resources.tab_files
import earth.walkthru.opensensor.today.resources.tab_history
import earth.walkthru.opensensor.today.resources.tab_today
import earth.walkthru.opensensor.today.resources.today_station_short
import earth.walkthru.opensensor.today.resources.today_title
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * What Today hands back to its platform: navigation, and the two actions that may need an OS
 * permission first. The platform asks for Bluetooth before [Reconnect] and for notifications before
 * [SmartSync], then calls [TodayController.reconnect] or [TodayController.smartSync]. [Disconnect]
 * is the platform's to run because it also leaves the screen.
 */
sealed interface TodayAction {
    data object OpenHistory : TodayAction
    data object OpenFiles : TodayAction
    data object OpenDevice : TodayAction
    data object OpenDebug : TodayAction
    data object OpenDevices : TodayAction
    data object OpenSettings : TodayAction
    data object OpenBluetoothSettings : TodayAction
    data object Disconnect : TodayAction
    data object Reconnect : TodayAction
    data object SmartSync : TodayAction
}

/**
 * Today on both platforms. Collects the [controller], shows its confirmation dialogs and its
 * notices, and draws [TodayContent]. Finalize requires confirmation; device UTC is automatic.
 */
@Composable
fun TodayScreen(
    controller: TodayController,
    units: UnitFormatter,
    onAction: (TodayAction) -> Unit,
    modifier: Modifier = Modifier,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    val state by controller.state.collectAsState()
    val dialog by controller.dialog.collectAsState()

    LaunchedEffect(controller) {
        controller.notices.collect { notice -> noticeText(notice)?.let { snackbar.showSnackbar(it) } }
    }

    TodayContent(
        state = state,
        units = units,
        snackbar = snackbar,
        modifier = modifier,
        onAction = onAction,
        onRequestFlush = controller::requestFlush,
    )

    dialog?.let { TodayDialogs(it, onConfirm = controller::confirm, onDismiss = controller::dismiss) }
}

/** The screen title, for a platform control that opens [TodayScreen]. */
@Composable
fun todayTitle(): String = stringResource(Res.string.today_title)

/** A notice in words, or null for one that opens a dialog instead. */
internal suspend fun noticeText(notice: TodayNotice): String? = when (notice) {
    TodayNotice.ClockSet -> getString(Res.string.msg_clock_set)
    is TodayNotice.Finalized -> getPluralString(Res.plurals.msg_finalized, notice.rows, notice.rows, notice.files)
    is TodayNotice.Rejected -> getString(if (notice.command == TodayCommand.SET_TIME) Res.string.msg_rejected_set_time else Res.string.msg_rejected_flush)
    is TodayNotice.Failed -> getString(if (notice.command == TodayCommand.SET_TIME) Res.string.msg_failed_set_time else Res.string.msg_failed_flush)
    TodayNotice.Busy -> getString(Res.string.msg_busy)
}

/** The open confirmation, one at a time. Cancel and Not now send nothing. */
@Composable
internal fun TodayDialogs(dialog: TodayDialog, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    when (dialog) {
        TodayDialog.Flush -> AlertDialog(
            onDismissRequest = onDismiss,
            icon = { Icon(AppIcons.Storage, contentDescription = null) },
            title = { Text(stringResource(Res.string.flush)) },
            text = { Text(stringResource(Res.string.flush_confirm)) },
            confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(Res.string.flush_button)) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } },
        )
    }
}

/**
 * Today, stateless. Simple shows the hero reading, today so far, the sync state in words with one
 * primary action, and the clock. Detailed adds the other measurements, board, storage, batch
 * estimate and the device card. Developer adds identifiers and link internals. The card list is
 * [todayCards], the same on both platforms.
 */
@Composable
fun TodayContent(
    state: TodayUiState,
    units: UnitFormatter,
    onAction: (TodayAction) -> Unit,
    onRequestFlush: () -> Unit,
    modifier: Modifier = Modifier,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    val snapshot = state.snapshot
    val level = state.level
    val profile = state.capabilities.liveProfile
    val live = snapshot.live
    val receivedAt = snapshot.liveAtEpochMs
    // Hoisted so the scroll position belongs to the screen. A clock refresh must never reset it.
    val listState = rememberLazyListState()

    CompositionLocalProvider(LocalTodayUnits provides units) {
        Scaffold(
            modifier = modifier,
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                Column {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                        title = {
                            Column {
                                // The device name titles the page (TalkBack headings list, VoiceOver rotor).
                                Text(
                                    snapshot.name ?: stringResource(Res.string.device_unnamed),
                                    style = MaterialTheme.typography.titleLarge,
                                    modifier = Modifier.semantics { heading() },
                                )
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TransportChip(snapshot.transport)
                                    if (level.developer) {
                                        snapshot.info?.station?.let {
                                            Text(
                                                stringResource(Res.string.today_station_short, it.take(8)),
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
                            TextButton(onClick = { onAction(TodayAction.OpenDevices) }) { Text(stringResource(Res.string.nav_devices)) }
                            if (level.developer) {
                                IconButton(onClick = { onAction(TodayAction.OpenDebug) }) { Icon(AppIcons.BugReport, contentDescription = stringResource(Res.string.nav_debug)) }
                            }
                            IconButton(onClick = { onAction(TodayAction.OpenSettings) }) { Icon(AppIcons.Settings, contentDescription = stringResource(Res.string.nav_settings)) }
                            IconButton(onClick = { onAction(TodayAction.Disconnect) }) { Icon(AppIcons.LinkOff, contentDescription = stringResource(Res.string.nav_disconnect)) }
                        },
                    )
                    DeviceSectionTabs { index ->
                        when (index) {
                            1 -> onAction(TodayAction.OpenHistory)
                            2 -> onAction(TodayAction.OpenFiles)
                            3 -> onAction(TodayAction.OpenDevice)
                            else -> Unit
                        }
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
                // The banner lives outside the list, so no list index shifts when it comes and goes.
                if (snapshot.connection !is ConnectionState.Ready) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        ConnectionBanner(
                            snapshot.connection,
                            reconnecting = snapshot.reconnecting,
                            technical = level.developer,
                            pairingRepair = snapshot.needsPairingRepair,
                            onOpenBluetoothSettings = { onAction(TodayAction.OpenBluetoothSettings) },
                        )
                        if (snapshot.reconnecting == null && (snapshot.connection is ConnectionState.Failed || snapshot.connection is ConnectionState.Disconnected)) {
                            Spacer(Modifier.height(8.dp))
                            PillButton(stringResource(Res.string.action_reconnect), onClick = { onAction(TodayAction.Reconnect) }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    state.cards.forEach { card ->
                        item(key = card.key) {
                            when (card) {
                                TodayCard.NOW_AIR -> MetricHeroCard(live, receivedAt, level)
                                TodayCard.NOW_ENVIRONMENT -> EnvironmentCard(profile, live, receivedAt, hero = true)
                                TodayCard.TODAY_SO_FAR -> TodaySoFarCard(snapshot.todaySoFar, clockMissing = snapshot.status?.utcSynced == false, level = level)
                                TodayCard.ENVIRONMENT -> EnvironmentCard(profile, live, receivedAt, hero = false)
                                TodayCard.SMART_SYNC -> SmartSyncCard(
                                    sync = snapshot.sync,
                                    archive = snapshot.archive,
                                    enabled = snapshot.connection.isReady,
                                    level = level,
                                    onSync = { onAction(TodayAction.SmartSync) },
                                )
                                TodayCard.CLOCK -> ClockCard(
                                    status = snapshot.status,
                                    live = live,
                                    enabled = snapshot.connection.isReady,
                                    level = level,
                                    onFlush = onRequestFlush,
                                )
                                TodayCard.BATCH_ESTIMATE -> BatchEstimateCard(live, receivedAt, snapshot.config)
                                TodayCard.READINGS -> ReadingsCard(live, receivedAt, level)
                                TodayCard.PARTICLES -> ParticlesCard(live)
                                TodayCard.BOARD -> BoardCard(live, profile, level)
                                TodayCard.STORAGE -> StorageCard(snapshot.status, snapshot.statusAtEpochMs, level)
                                TodayCard.DEVICE_INFO -> DeviceInfoCard(
                                    name = snapshot.name,
                                    info = snapshot.info,
                                    connection = snapshot.connection,
                                    transport = snapshot.transport,
                                    level = level,
                                    pairingRepair = snapshot.needsPairingRepair,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The link state as a banner, with the reconnect attempt and, when pairing broke, a Bluetooth settings link. */
@Composable
internal fun ConnectionBanner(
    state: ConnectionState,
    reconnecting: TodayReconnect?,
    technical: Boolean,
    pairingRepair: Boolean,
    onOpenBluetoothSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (container, content) = when (state) {
        is ConnectionState.Ready -> MaterialTheme.colorScheme.surfaceContainer to MaterialTheme.colorScheme.onSurface
        is ConnectionState.Failed -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        ConnectionState.Bonding -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.large, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.isBusy) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                StatusDot(
                    when (state) {
                        is ConnectionState.Ready -> MaterialTheme.colorScheme.primary
                        is ConnectionState.Failed -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.outline
                    },
                )
            }
            Spacer(Modifier.width(12.dp))
            Column {
                // The link state changes while the user waits, so screen readers hear it without moving focus.
                Text(
                    state.describe(technical, pairingRepair),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                if (reconnecting != null) {
                    Text(
                        if (technical) {
                            stringResource(Res.string.conn_link_lost_detail, reconnecting.reason, reconnecting.attempt, reconnecting.maxAttempts)
                        } else {
                            stringResource(Res.string.conn_link_lost)
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (pairingRepair && state is ConnectionState.Failed) {
                    TextButton(onClick = onOpenBluetoothSettings) { Text(stringResource(Res.string.conn_bluetooth_settings)) }
                }
            }
        }
    }
}

/** The four sections of one device, Today selected. The bottom bar stays below with My devices selected. */
@Composable
internal fun DeviceSectionTabs(containerColor: Color = MaterialTheme.colorScheme.background, onSelect: (Int) -> Unit) {
    SectionTabs(
        labels = listOf(
            stringResource(Res.string.tab_today),
            stringResource(Res.string.tab_history),
            stringResource(Res.string.tab_files),
            stringResource(Res.string.tab_device),
        ),
        selectedIndex = 0,
        onSelect = onSelect,
        containerColor = containerColor,
    )
}

/** Small chip naming the active byte pipe, Bluetooth or Wi-Fi LAN (and the fake device in debug builds). */
@Composable
internal fun TransportChip(kind: TransportKind?, modifier: Modifier = Modifier) {
    if (kind == null) return
    val (icon, container, content) = when (kind) {
        TransportKind.BLE -> Triple(AppIcons.Bluetooth, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        TransportKind.LAN -> Triple(AppIcons.Wifi, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
        TransportKind.FAKE -> Triple(AppIcons.BugReport, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
    }
    TagChip(icon, kind.labelText(), container, content, modifier)
}
