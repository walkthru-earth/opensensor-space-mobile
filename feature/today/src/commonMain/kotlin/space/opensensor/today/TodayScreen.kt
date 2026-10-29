package space.opensensor.today

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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import space.opensensor.designsystem.AppIcons
import space.opensensor.designsystem.BodyText
import space.opensensor.designsystem.FitText
import space.opensensor.today.resources.Res
import space.opensensor.today.resources.action_cancel
import space.opensensor.today.resources.action_reconnect
import space.opensensor.today.resources.device_unnamed
import space.opensensor.today.resources.flush
import space.opensensor.today.resources.flush_button
import space.opensensor.today.resources.flush_confirm
import space.opensensor.today.resources.msg_busy
import space.opensensor.today.resources.msg_failed_flush
import space.opensensor.today.resources.msg_finalized
import space.opensensor.today.resources.msg_rejected_flush
import space.opensensor.today.resources.nav_debug
import space.opensensor.today.resources.nav_devices
import space.opensensor.today.resources.nav_disconnect
import space.opensensor.today.resources.today_forget
import space.opensensor.today.resources.today_forget_body
import space.opensensor.today.resources.today_forget_bond_ios
import space.opensensor.today.resources.today_forget_button
import space.opensensor.today.resources.today_forget_title
import space.opensensor.today.resources.today_forget_unnamed
import space.opensensor.today.resources.today_more
import space.opensensor.today.resources.today_thing_short
import space.opensensor.units.UnitFormatter

/**
 * What Today hands back to its platform: navigation, and the two actions that may need an OS
 * permission first. The platform asks for Bluetooth before [Reconnect] and for notifications before
 * [SmartSync], then calls [TodayController.reconnect] or [TodayController.smartSync]. [Disconnect]
 * and [Forget] are the platform's to run because they also leave the screen.
 */
sealed interface TodayAction {
    data object OpenHistory : TodayAction
    data object OpenFiles : TodayAction
    data object OpenDevice : TodayAction
    data object OpenDebug : TodayAction
    data object OpenDevices : TodayAction
    data object OpenBluetoothSettings : TodayAction
    data object Disconnect : TodayAction

    /** The user confirmed Forget. The platform forgets the sensor on this phone and leaves Today. */
    data object Forget : TodayAction
    data object Reconnect : TodayAction
    data object SmartSync : TodayAction

    /** Stops this phone's own run. A run that background work holds is never offered. */
    data object StopSync : TodayAction
}

/**
 * Today on both platforms. Collects the [controller], shows its confirmation dialogs and its
 * notices, and draws [TodayContent]. Finalize and Forget require confirmation. Device UTC is
 * automatic. [explainSystemBond] adds the iOS line that the system keeps the Bluetooth pairing.
 */
@Composable
fun TodayScreen(
    controller: TodayController,
    units: UnitFormatter,
    onAction: (TodayAction) -> Unit,
    modifier: Modifier = Modifier,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    explainSystemBond: Boolean = false,
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
        onRequestForget = controller::requestForget,
    )

    dialog?.let { open ->
        TodayDialogs(
            open,
            name = state.snapshot.name,
            explainSystemBond = explainSystemBond,
            onConfirm = {
                controller.confirm()
                if (open == TodayDialog.Forget) onAction(TodayAction.Forget)
            },
            onDismiss = controller::dismiss,
        )
    }
}

/** A notice in words, or null for one that opens a dialog instead. */
internal suspend fun noticeText(notice: TodayNotice): String? = when (notice) {
    is TodayNotice.Finalized -> getPluralString(Res.plurals.msg_finalized, notice.rows, notice.rows, notice.files)
    is TodayNotice.Rejected -> getString(Res.string.msg_rejected_flush)
    is TodayNotice.Failed -> getString(Res.string.msg_failed_flush)
    TodayNotice.Busy -> getString(Res.string.msg_busy)
}

/** The open confirmation, one at a time. Cancel and Not now send nothing. Titles are headings. */
@Composable
internal fun TodayDialogs(
    dialog: TodayDialog,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    name: String? = null,
    explainSystemBond: Boolean = false,
) {
    when (dialog) {
        TodayDialog.Flush -> AlertDialog(
            onDismissRequest = onDismiss,
            icon = { Icon(AppIcons.Storage, contentDescription = null) },
            title = { BodyText(stringResource(Res.string.flush), heading = true) },
            text = { BodyText(stringResource(Res.string.flush_confirm)) },
            confirmButton = { TextButton(onClick = onConfirm) { FitText(stringResource(Res.string.flush_button)) } },
            dismissButton = { TextButton(onClick = onDismiss) { FitText(stringResource(Res.string.action_cancel)) } },
        )
        TodayDialog.Forget -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { BodyText(stringResource(Res.string.today_forget_title, name ?: stringResource(Res.string.today_forget_unnamed)), heading = true) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BodyText(stringResource(Res.string.today_forget_body))
                    if (explainSystemBond) BodyText(stringResource(Res.string.today_forget_bond_ios))
                }
            },
            confirmButton = { TextButton(onClick = onConfirm) { FitText(stringResource(Res.string.today_forget_button)) } },
            dismissButton = { TextButton(onClick = onDismiss) { FitText(stringResource(Res.string.action_cancel)) } },
        )
    }
}

/** More options in the thing header. Forget lives here, on the sensor's own page (#117). */
@Composable
private fun MoreMenu(onForget: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(AppIcons.MoreVert, contentDescription = stringResource(Res.string.today_more)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.today_forget)) },
                onClick = {
                    open = false
                    onForget()
                },
            )
        }
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
    onRequestForget: () -> Unit = {},
) {
    val snapshot = state.snapshot
    val level = state.level
    val capabilities = state.capabilities
    val reading = state.reading
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
                                // Fits to one line, then wraps only between words, so "OS-7500" never splits (#143).
                                FitText(
                                    snapshot.name ?: stringResource(Res.string.device_unnamed),
                                    style = MaterialTheme.typography.titleLarge,
                                    modifier = Modifier.semantics { heading() },
                                )
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    // The pipe is a detail. Simple never names Bluetooth or Wi-Fi.
                                    if (level.detailed) TransportChip(snapshot.transport)
                                    if (level.developer) {
                                        snapshot.info?.thing?.let {
                                            FitText(
                                                stringResource(Res.string.today_thing_short, it.take(8)),
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
                            // Reconnect and Disconnect live only here, in the thing header.
                            if (snapshot.thing.offersReconnect) {
                                TextButton(onClick = { onAction(TodayAction.Reconnect) }) { Text(stringResource(Res.string.action_reconnect)) }
                            } else {
                                IconButton(onClick = { onAction(TodayAction.Disconnect) }) { Icon(AppIcons.LinkOff, contentDescription = stringResource(Res.string.nav_disconnect)) }
                            }
                            MoreMenu(onForget = onRequestForget)
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
                val thing = snapshot.thing
                if (thing.showsBanner) {
                    Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        ConnectionBanner(
                            snapshot.connection,
                            reconnecting = snapshot.reconnecting,
                            technical = level.developer,
                            pairingRepair = snapshot.needsPairingRepair,
                            onOpenBluetoothSettings = { onAction(TodayAction.OpenBluetoothSettings) },
                            heldInBackground = thing.heldInBackground,
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
                                TodayCard.NOW -> state.presenter.Hero(state, Modifier)
                                TodayCard.TODAY_SO_FAR -> TodaySoFarCard(snapshot.todaySoFar, level = level)
                                TodayCard.ENVIRONMENT -> EnvironmentCard(state.liveSupport, capabilities, reading, receivedAt, hero = false)
                                TodayCard.SMART_SYNC -> SmartSyncCard(
                                    sync = snapshot.sync,
                                    archive = snapshot.archive,
                                    enabled = snapshot.thing.canCommand,
                                    level = level,
                                    onSync = { onAction(TodayAction.SmartSync) },
                                    onStop = { onAction(TodayAction.StopSync) }.takeIf { snapshot.sync.running && !snapshot.heldInBackground },
                                )
                                TodayCard.CLOCK -> ClockCard(status = snapshot.status, live = live)
                                TodayCard.BATCH_ESTIMATE -> BatchEstimateCard(reading, receivedAt, snapshot.config)
                                TodayCard.READINGS -> ReadingsCard(reading, receivedAt, level)
                                TodayCard.PARTICLES -> ParticlesCard(reading)
                                TodayCard.METRICS -> MetricsCard(state)
                                TodayCard.BOARD -> BoardCard(
                                    support = state.liveSupport,
                                    live = reading,
                                    capabilities = capabilities,
                                    power = state.power,
                                    showTemperature = state.presenter.boardTemperature(state),
                                    level = level,
                                )
                                TodayCard.STORAGE -> StorageCard(
                                    status = snapshot.status,
                                    receivedAtEpochMs = snapshot.statusAtEpochMs,
                                    level = level,
                                    enabled = snapshot.thing.canCommand,
                                    onFlush = onRequestFlush,
                                )
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
