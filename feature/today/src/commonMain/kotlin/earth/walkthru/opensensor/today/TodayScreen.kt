package earth.walkthru.opensensor.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import earth.walkthru.aqsync.units.UnitFormatter
import earth.walkthru.opensensor.designsystem.AppIcons
import earth.walkthru.opensensor.today.resources.Res
import earth.walkthru.opensensor.today.resources.action_cancel
import earth.walkthru.opensensor.today.resources.action_reconnect
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
    data object OpenBluetoothSettings : TodayAction
    data object Disconnect : TodayAction
    data object Reconnect : TodayAction
    data object SmartSync : TodayAction

    /** Stops this phone's own run. A run that background work holds is never offered. */
    data object StopSync : TodayAction
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
                                    // The pipe is a detail. Simple never names Bluetooth or Wi-Fi.
                                    if (level.detailed) TransportChip(snapshot.transport)
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
                            // Reconnect and Disconnect live only here, in the station header.
                            if (snapshot.station.offersReconnect) {
                                TextButton(onClick = { onAction(TodayAction.Reconnect) }) { Text(stringResource(Res.string.action_reconnect)) }
                            } else {
                                IconButton(onClick = { onAction(TodayAction.Disconnect) }) { Icon(AppIcons.LinkOff, contentDescription = stringResource(Res.string.nav_disconnect)) }
                            }
                        },
                    )
                    DeviceSectionTabs(DeviceTab.TODAY) { tab ->
                        when (tab) {
                            DeviceTab.HISTORY -> onAction(TodayAction.OpenHistory)
                            DeviceTab.FILES -> onAction(TodayAction.OpenFiles)
                            DeviceTab.DEVICE -> onAction(TodayAction.OpenDevice)
                            DeviceTab.TODAY -> Unit
                        }
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
                // The banner lives outside the list, so no list index shifts when it comes and goes.
                val station = snapshot.station
                if (station.showsBanner) {
                    Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        ConnectionBanner(
                            snapshot.connection,
                            reconnecting = snapshot.reconnecting,
                            technical = level.developer,
                            pairingRepair = snapshot.needsPairingRepair,
                            onOpenBluetoothSettings = { onAction(TodayAction.OpenBluetoothSettings) },
                            heldInBackground = station.heldInBackground,
                        )
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
                                    enabled = snapshot.station.canCommand,
                                    level = level,
                                    onSync = { onAction(TodayAction.SmartSync) },
                                    onStop = { onAction(TodayAction.StopSync) }.takeIf { snapshot.sync.running && !snapshot.heldInBackground },
                                )
                                TodayCard.CLOCK -> ClockCard(
                                    status = snapshot.status,
                                    live = live,
                                    enabled = snapshot.station.canCommand,
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
