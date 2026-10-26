package space.opensensor.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import space.opensensor.data.protocol.ConfigProblem
import space.opensensor.designsystem.AppIcons
import space.opensensor.designsystem.InfoNote
import space.opensensor.device.resources.Res
import space.opensensor.device.resources.action_cancel
import space.opensensor.device.resources.action_reconnect
import space.opensensor.device.resources.bonds_confirm
import space.opensensor.device.resources.bonds_forget
import space.opensensor.device.resources.bonds_forget_button
import space.opensensor.device.resources.config_problem_line_break
import space.opensensor.device.resources.config_problem_pin
import space.opensensor.device.resources.config_problem_psk_long
import space.opensensor.device.resources.config_problem_ssid_empty
import space.opensensor.device.resources.config_problem_ssid_long
import space.opensensor.device.resources.device_back
import space.opensensor.device.resources.device_not_ready
import space.opensensor.device.resources.device_refresh
import space.opensensor.device.resources.device_screen_title
import space.opensensor.device.resources.lan_reconnect_ble
import space.opensensor.device.resources.msg_bonds_cleared
import space.opensensor.device.resources.msg_busy
import space.opensensor.device.resources.msg_config_key_rejected
import space.opensensor.device.resources.msg_failed_get_config
import space.opensensor.device.resources.msg_failed_get_token
import space.opensensor.device.resources.msg_failed_reboot
import space.opensensor.device.resources.msg_failed_rotate_token
import space.opensensor.device.resources.msg_failed_set_config
import space.opensensor.device.resources.msg_lan_off
import space.opensensor.device.resources.msg_lan_on
import space.opensensor.device.resources.msg_lan_token_stored
import space.opensensor.device.resources.msg_pairing_saved
import space.opensensor.device.resources.msg_rebooting
import space.opensensor.device.resources.msg_rejected_get_config
import space.opensensor.device.resources.msg_rejected_get_token
import space.opensensor.device.resources.msg_rejected_reboot
import space.opensensor.device.resources.msg_rejected_rotate_token
import space.opensensor.device.resources.msg_rejected_set_config
import space.opensensor.device.resources.msg_saved_after_reboot
import space.opensensor.device.resources.msg_secret_needs_bluetooth
import space.opensensor.device.resources.msg_sensor_busy
import space.opensensor.device.resources.msg_sensor_failed
import space.opensensor.device.resources.msg_sensor_not_confirmed
import space.opensensor.device.resources.msg_sensor_saved_off
import space.opensensor.device.resources.msg_sensor_saved_on
import space.opensensor.device.resources.msg_sticker_invalid
import space.opensensor.device.resources.msg_token_rotated_fetch
import space.opensensor.device.resources.msg_token_rotated_stored
import space.opensensor.device.resources.msg_wifi_off
import space.opensensor.device.resources.msg_wifi_on
import space.opensensor.device.resources.msg_wifi_sent
import space.opensensor.device.resources.pairing_none_button
import space.opensensor.device.resources.pairing_none_confirm
import space.opensensor.device.resources.pairing_none_title
import space.opensensor.device.resources.reboot_button
import space.opensensor.device.resources.reboot_confirm
import space.opensensor.device.resources.reboot_title
import space.opensensor.device.resources.rotate_button
import space.opensensor.device.resources.rotate_confirm
import space.opensensor.device.resources.rotate_title
import space.opensensor.permissions.RuntimeAccess

/**
 * Everything the shared Device section can ask for. [DeviceConfigController.onAction] runs all of
 * them except the two that need the platform first: [RequestLanAccess] (the OS local network
 * prompt) and [ScanSticker] (a camera barcode scanner). Device changing ones open a confirmation in
 * the controller, nothing reaches the device from here directly.
 */
sealed interface DeviceConfigAction {
    data class LocationResolution(val resolution: Int) : DeviceConfigAction
    data class LocationCountry(val country: String) : DeviceConfigAction
    data class LocationCell(val cell: String, val resolution: Int) : DeviceConfigAction
    data object SaveLocation : DeviceConfigAction
    data object ClearLocation : DeviceConfigAction
    data object Refresh : DeviceConfigAction
    data class SavePairing(val choice: PairingChoice) : DeviceConfigAction
    data object RequestReboot : DeviceConfigAction
    data class SetWifiOn(val on: Boolean) : DeviceConfigAction
    data object ScanWifi : DeviceConfigAction
    data object ClearWifiScan : DeviceConfigAction

    /** Prints the network name only, [WifiJoin] redacts the passphrase. */
    data class JoinWifi(val join: WifiJoin) : DeviceConfigAction
    data object RequestLanAccess : DeviceConfigAction
    data class SetLanOn(val on: Boolean) : DeviceConfigAction
    data object FetchToken : DeviceConfigAction
    data object RequestRotateToken : DeviceConfigAction
    data object ReconnectOverBluetooth : DeviceConfigAction
    data object RequestForgetBonds : DeviceConfigAction
    data object ScanSticker : DeviceConfigAction
    data class SaveSensor(val sticker: String, val batchEstimate: Boolean, val source: String) : DeviceConfigAction
}

/**
 * Runs one [action] through the controller. Returns false for [DeviceConfigAction.RequestLanAccess]
 * and [DeviceConfigAction.ScanSticker], which the platform handles itself.
 */
fun DeviceConfigController.onAction(action: DeviceConfigAction): Boolean {
    when (action) {
        is DeviceConfigAction.LocationResolution -> selectLocationResolution(action.resolution)
        is DeviceConfigAction.LocationCountry -> selectLocationCountry(action.country)
        is DeviceConfigAction.LocationCell -> acceptLocationCell(action.cell, action.resolution)
        DeviceConfigAction.SaveLocation -> saveLocation()
        DeviceConfigAction.ClearLocation -> clearLocation()
        DeviceConfigAction.Refresh -> refresh()
        is DeviceConfigAction.SavePairing -> savePairing(action.choice)
        DeviceConfigAction.RequestReboot -> requestReboot()
        is DeviceConfigAction.SetWifiOn -> setWifiOn(action.on)
        DeviceConfigAction.ScanWifi -> scanWifi()
        DeviceConfigAction.ClearWifiScan -> clearWifiScan()
        is DeviceConfigAction.JoinWifi -> joinWifi(action.join)
        is DeviceConfigAction.SetLanOn -> setLanOn(action.on)
        DeviceConfigAction.FetchToken -> fetchToken()
        DeviceConfigAction.RequestRotateToken -> requestRotateToken()
        DeviceConfigAction.ReconnectOverBluetooth -> reconnectOverBluetooth()
        DeviceConfigAction.RequestForgetBonds -> requestForgetBonds()
        is DeviceConfigAction.SaveSensor -> saveSensor(action.sticker, action.batchEstimate, action.source)
        DeviceConfigAction.RequestLanAccess, DeviceConfigAction.ScanSticker -> return false
    }
    return true
}

/**
 * The fitted sensor card, for boards with `sensorIdentity`, at Detailed. [onScan] is null where the
 * platform has no barcode scanner, manual entry still works.
 */
fun LazyListScope.sensorIdentityItem(
    state: DeviceConfigUiState,
    scan: StickerScan?,
    canScan: Boolean,
    onAction: (DeviceConfigAction) -> Unit,
) {
    if (state.capabilities.sensorIdentity && state.level.detailed) {
        item(key = "sensor") {
            SensorIdentityCard(
                state = state,
                scan = scan,
                onScan = if (canScan) ({ onAction(DeviceConfigAction.ScanSticker) }) else null,
                onSave = { sticker, batch, source -> onAction(DeviceConfigAction.SaveSensor(sticker, batch, source)) },
            )
        }
    }
}

/**
 * The configuration cards in their order. A protocol v1 device gets one note. Pairing is Detailed,
 * or Simple when it needs attention. Wi-Fi and Sync over Wi-Fi show at every level, paired phones at
 * Detailed.
 */
fun LazyListScope.deviceConfigItems(state: DeviceConfigUiState, lanAccess: LanAccess, onAction: (DeviceConfigAction) -> Unit) {
    if (state.snapshot.info != null && !state.isV2) {
        item(key = "v1") { ProtocolV1Card() }
    }
    if (!state.isV2) return
    if (state.level.detailed || state.pairingNeedsAttention) {
        item(key = "pairing") {
            PairingCard(
                state,
                onSave = { onAction(DeviceConfigAction.SavePairing(it)) },
                onReboot = { onAction(DeviceConfigAction.RequestReboot) },
            )
        }
    }
    item(key = "location") { DeviceLocationCard(state, onAction) }
    item(key = "wifi") {
        WifiCard(
            state = state,
            onToggleWifi = { onAction(DeviceConfigAction.SetWifiOn(it)) },
            onScan = { onAction(DeviceConfigAction.ScanWifi) },
            onClearScan = { onAction(DeviceConfigAction.ClearWifiScan) },
            onJoin = { onAction(DeviceConfigAction.JoinWifi(it)) },
        )
    }
    item(key = "lan") {
        LanCard(
            state = state,
            access = lanAccess,
            onRequestAccess = { onAction(DeviceConfigAction.RequestLanAccess) },
            onToggleLan = { onAction(DeviceConfigAction.SetLanOn(it)) },
            onFetchToken = { onAction(DeviceConfigAction.FetchToken) },
            onRotateToken = { onAction(DeviceConfigAction.RequestRotateToken) },
            onReconnectBle = { onAction(DeviceConfigAction.ReconnectOverBluetooth) },
        )
    }
    if (state.level.detailed) {
        item(key = "bonds") { BondsCard(state, onForgetAll = { onAction(DeviceConfigAction.RequestForgetBonds) }) }
    }
}

/**
 * The controller's open confirmation and its notices, for a platform screen that draws its own list.
 * The snackbar's Bluetooth reconnect runs [reconnectOverBluetooth].
 */
@Composable
fun DeviceConfigEffects(
    controller: DeviceConfigController,
    snackbar: SnackbarHostState,
    reconnectOverBluetooth: () -> Unit = controller::reconnectOverBluetooth,
) {
    val dialog by controller.dialog.collectAsState()
    LaunchedEffect(controller) {
        controller.notices.collect { notice ->
            if (notice == DeviceConfigNotice.SecretNeedsBluetooth) {
                val result = snackbar.showSnackbar(deviceConfigNoticeText(notice), actionLabel = getString(Res.string.lan_reconnect_ble))
                if (result == SnackbarResult.ActionPerformed) reconnectOverBluetooth()
            } else {
                snackbar.showSnackbar(deviceConfigNoticeText(notice))
            }
        }
    }
    dialog?.let { DeviceConfigDialogs(it, onConfirm = controller::confirm, onDismiss = controller::dismiss) }
}

/** The open confirmation, one at a time. Cancel and a tap outside send nothing. */
@Composable
fun DeviceConfigDialogs(dialog: DeviceConfigDialog, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val (title, body, button) = when (dialog) {
        DeviceConfigDialog.Reboot -> Triple(Res.string.reboot_title, Res.string.reboot_confirm, Res.string.reboot_button)
        DeviceConfigDialog.RotateToken -> Triple(Res.string.rotate_title, Res.string.rotate_confirm, Res.string.rotate_button)
        DeviceConfigDialog.ForgetBonds -> Triple(Res.string.bonds_forget, Res.string.bonds_confirm, Res.string.bonds_forget_button)
        DeviceConfigDialog.PairingNone -> Triple(Res.string.pairing_none_title, Res.string.pairing_none_confirm, Res.string.pairing_none_button)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = if (dialog == DeviceConfigDialog.Reboot) ({ Icon(AppIcons.RestartAlt, contentDescription = null) }) else null,
        title = { Text(stringResource(title), Modifier.semantics { heading() }) },
        text = { Text(stringResource(body)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(button)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } },
    )
}

/** A notice in words. None carries a passkey, passphrase or token. */
suspend fun deviceConfigNoticeText(notice: DeviceConfigNotice): String = when (notice) {
    is DeviceConfigNotice.Problem -> getString(
        when (notice.problem) {
            ConfigProblem.PIN_FORMAT -> Res.string.config_problem_pin
            ConfigProblem.SSID_EMPTY -> Res.string.config_problem_ssid_empty
            ConfigProblem.SSID_TOO_LONG -> Res.string.config_problem_ssid_long
            ConfigProblem.PSK_TOO_LONG -> Res.string.config_problem_psk_long
            ConfigProblem.STICKER_FORMAT -> Res.string.msg_sticker_invalid
            ConfigProblem.LINE_BREAK -> Res.string.config_problem_line_break
        },
    )
    DeviceConfigNotice.StickerInvalid -> getString(Res.string.msg_sticker_invalid)
    DeviceConfigNotice.SensorBusy -> getString(Res.string.msg_sensor_busy)
    is DeviceConfigNotice.SensorSaved -> getString(if (notice.batchEstimate) Res.string.msg_sensor_saved_on else Res.string.msg_sensor_saved_off)
    DeviceConfigNotice.SensorNotConfirmed -> getString(Res.string.msg_sensor_not_confirmed)
    DeviceConfigNotice.SensorFailed -> getString(Res.string.msg_sensor_failed)
    DeviceConfigNotice.Busy -> getString(Res.string.msg_busy)
    is DeviceConfigNotice.Saved -> {
        val line = getString(
            when (notice.change) {
                DeviceConfigChange.PAIRING -> Res.string.msg_pairing_saved
                DeviceConfigChange.WIFI_SENT -> Res.string.msg_wifi_sent
                DeviceConfigChange.WIFI_ON -> Res.string.msg_wifi_on
                DeviceConfigChange.WIFI_OFF -> Res.string.msg_wifi_off
                DeviceConfigChange.LAN_ON -> Res.string.msg_lan_on
                DeviceConfigChange.LAN_OFF -> Res.string.msg_lan_off
                DeviceConfigChange.BONDS_CLEARED -> Res.string.msg_bonds_cleared
            },
        )
        if (notice.rebootRequired) getString(Res.string.msg_saved_after_reboot, line) else line
    }
    is DeviceConfigNotice.KeyRejected -> getString(Res.string.msg_config_key_rejected, notice.key)
    DeviceConfigNotice.SecretNeedsBluetooth -> getString(Res.string.msg_secret_needs_bluetooth)
    is DeviceConfigNotice.Rejected -> getString(
        when (notice.command) {
            DeviceConfigCommand.GET_CONFIG -> Res.string.msg_rejected_get_config
            DeviceConfigCommand.SET_CONFIG -> Res.string.msg_rejected_set_config
            DeviceConfigCommand.REBOOT -> Res.string.msg_rejected_reboot
            DeviceConfigCommand.GET_TOKEN -> Res.string.msg_rejected_get_token
            DeviceConfigCommand.ROTATE_TOKEN -> Res.string.msg_rejected_rotate_token
        },
    )
    is DeviceConfigNotice.Failed -> getString(
        when (notice.command) {
            DeviceConfigCommand.GET_CONFIG -> Res.string.msg_failed_get_config
            DeviceConfigCommand.SET_CONFIG -> Res.string.msg_failed_set_config
            DeviceConfigCommand.REBOOT -> Res.string.msg_failed_reboot
            DeviceConfigCommand.GET_TOKEN -> Res.string.msg_failed_get_token
            DeviceConfigCommand.ROTATE_TOKEN -> Res.string.msg_failed_rotate_token
        },
    )
    DeviceConfigNotice.Rebooting -> getString(Res.string.msg_rebooting)
    DeviceConfigNotice.TokenStored -> getString(Res.string.msg_lan_token_stored)
    is DeviceConfigNotice.TokenRotated -> getString(if (notice.stored) Res.string.msg_token_rotated_stored else Res.string.msg_token_rotated_fetch)
}

/**
 * The Device section as its own page, for iOS: back, title, refresh, the cards and the snackbar.
 * Android draws the same cards inside its Device screen with the device header and Auto-sync.
 * [lanAccess] reflects the platform's local network permission, [onPlatformAction] gets the two
 * actions the controller leaves to the platform. [lead] is the platform's first card, the iOS
 * Auto-sync switch. Reconnect sits in the top bar, the station header of this page. With
 * [bluetooth] both reconnect taps ask for Bluetooth first, see [ReconnectTaps].
 */
@Composable
fun DeviceConfigScreen(
    controller: DeviceConfigController,
    lanAccess: LanAccess,
    onBack: () -> Unit,
    onPlatformAction: (DeviceConfigAction) -> Unit,
    modifier: Modifier = Modifier,
    scan: StickerScan? = null,
    canScan: Boolean = false,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    lead: (@Composable () -> Unit)? = null,
    bluetooth: RuntimeAccess? = null,
) {
    val state by controller.state.collectAsState()
    val taps = ReconnectTaps(bluetooth, controller::reconnect, controller::reconnectOverBluetooth)
    DeviceConfigEffects(controller, snackbar, taps::overBluetooth)
    val onAction: (DeviceConfigAction) -> Unit = { action ->
        if (action == DeviceConfigAction.ReconnectOverBluetooth) {
            taps.overBluetooth()
        } else if (!controller.onAction(action)) {
            onPlatformAction(action)
        }
    }
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(AppIcons.ArrowBack, contentDescription = stringResource(Res.string.device_back)) }
                },
                title = {
                    Column {
                        Text(stringResource(Res.string.device_screen_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                        state.snapshot.name?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                },
                actions = {
                    if (state.snapshot.station.offersReconnect) {
                        TextButton(onClick = taps::reconnect) { Text(stringResource(Res.string.action_reconnect)) }
                    }
                    IconButton(onClick = { onAction(DeviceConfigAction.Refresh) }, enabled = state.ready && state.isV2) {
                        Icon(AppIcons.Refresh, contentDescription = stringResource(Res.string.device_refresh))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (!state.ready) {
                item(key = "not_ready") { InfoNote(stringResource(Res.string.device_not_ready)) }
            }
            if (lead != null) item(key = "lead") { lead() }
            sensorIdentityItem(state, scan, canScan, onAction)
            deviceConfigItems(state, lanAccess, onAction)
        }
    }
}

/**
 * The page's two reconnect taps. With [bluetooth] each asks for Bluetooth first, from the tap, as
 * Today's Reconnect does. A refused [reconnect] still runs, so it can use Wi-Fi. A refused
 * [overBluetooth] does nothing, it needs Bluetooth. Without [bluetooth] both run as they are.
 */
internal class ReconnectTaps(
    private val bluetooth: RuntimeAccess?,
    private val onReconnect: () -> Unit,
    private val onReconnectOverBluetooth: () -> Unit,
) {
    fun reconnect() {
        if (bluetooth == null) onReconnect() else bluetooth.run(onDeclined = onReconnect, action = onReconnect)
    }

    fun overBluetooth() {
        if (bluetooth == null) onReconnectOverBluetooth() else bluetooth.run(action = onReconnectOverBluetooth)
    }
}
