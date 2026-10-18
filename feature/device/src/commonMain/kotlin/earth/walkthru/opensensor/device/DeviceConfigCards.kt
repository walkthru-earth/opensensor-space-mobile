package earth.walkthru.opensensor.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import earth.walkthru.aqsync.data.SensorLabel
import earth.walkthru.aqsync.data.WifiScanState
import earth.walkthru.aqsync.data.ble.TransportKind
import earth.walkthru.aqsync.data.protocol.ConfigPatch
import earth.walkthru.aqsync.data.protocol.Frame
import earth.walkthru.aqsync.data.protocol.PairMode
import earth.walkthru.aqsync.data.protocol.PlantowerBatchCandidate
import earth.walkthru.aqsync.data.protocol.WifiState
import earth.walkthru.opensensor.designsystem.AppIcons
import earth.walkthru.opensensor.designsystem.BrandCard
import earth.walkthru.opensensor.designsystem.CardHeader
import earth.walkthru.opensensor.designsystem.Eyebrow
import earth.walkthru.opensensor.designsystem.FloatingControl
import earth.walkthru.opensensor.designsystem.InfoContent
import earth.walkthru.opensensor.designsystem.InfoNote
import earth.walkthru.opensensor.designsystem.OutlinePillButton
import earth.walkthru.opensensor.designsystem.PeacockExpression
import earth.walkthru.opensensor.designsystem.PeacockIcon
import earth.walkthru.opensensor.designsystem.PillButton
import earth.walkthru.opensensor.designsystem.SignalBars
import earth.walkthru.opensensor.designsystem.StatLine
import earth.walkthru.opensensor.designsystem.StatusDot
import earth.walkthru.opensensor.designsystem.WarningNote
import earth.walkthru.opensensor.designsystem.rememberCredentialCaptureGuard
import earth.walkthru.opensensor.device.resources.Res
import earth.walkthru.opensensor.device.resources.action_cancel
import earth.walkthru.opensensor.device.resources.action_hide
import earth.walkthru.opensensor.device.resources.bonds_count
import earth.walkthru.opensensor.device.resources.bonds_explain
import earth.walkthru.opensensor.device.resources.bonds_forget
import earth.walkthru.opensensor.device.resources.bonds_title
import earth.walkthru.opensensor.device.resources.config_not_read
import earth.walkthru.opensensor.device.resources.info_pairing_body
import earth.walkthru.opensensor.device.resources.info_wifi_body
import earth.walkthru.opensensor.device.resources.lan_allow
import earth.walkthru.opensensor.device.resources.lan_clients
import earth.walkthru.opensensor.device.resources.lan_discovery_error
import earth.walkthru.opensensor.device.resources.lan_explain
import earth.walkthru.opensensor.device.resources.lan_fetch
import earth.walkthru.opensensor.device.resources.lan_fetch_again
import earth.walkthru.opensensor.device.resources.lan_fetch_ble_only
import earth.walkthru.opensensor.device.resources.lan_host
import earth.walkthru.opensensor.device.resources.lan_no_access
import earth.walkthru.opensensor.device.resources.lan_not_seen
import earth.walkthru.opensensor.device.resources.lan_port
import earth.walkthru.opensensor.device.resources.lan_reconnect_ble
import earth.walkthru.opensensor.device.resources.lan_rotate
import earth.walkthru.opensensor.device.resources.lan_seen
import earth.walkthru.opensensor.device.resources.lan_seen_at
import earth.walkthru.opensensor.device.resources.lan_server
import earth.walkthru.opensensor.device.resources.lan_server_on
import earth.walkthru.opensensor.device.resources.lan_title
import earth.walkthru.opensensor.device.resources.lan_token_explain
import earth.walkthru.opensensor.device.resources.lan_token_held
import earth.walkthru.opensensor.device.resources.lan_token_missing
import earth.walkthru.opensensor.device.resources.pairing_default_pin
import earth.walkthru.opensensor.device.resources.pairing_display
import earth.walkthru.opensensor.device.resources.pairing_fixed
import earth.walkthru.opensensor.device.resources.pairing_hide_pin
import earth.walkthru.opensensor.device.resources.pairing_label_fixed
import earth.walkthru.opensensor.device.resources.pairing_label_none
import earth.walkthru.opensensor.device.resources.pairing_label_random
import earth.walkthru.opensensor.device.resources.pairing_mode
import earth.walkthru.opensensor.device.resources.pairing_new_pin
import earth.walkthru.opensensor.device.resources.pairing_next_boot
import earth.walkthru.opensensor.device.resources.pairing_none
import earth.walkthru.opensensor.device.resources.pairing_pin
import earth.walkthru.opensensor.device.resources.pairing_random
import earth.walkthru.opensensor.device.resources.pairing_reboot_pending
import earth.walkthru.opensensor.device.resources.pairing_save
import earth.walkthru.opensensor.device.resources.pairing_show_pin
import earth.walkthru.opensensor.device.resources.pairing_six_digits
import earth.walkthru.opensensor.device.resources.pairing_title
import earth.walkthru.opensensor.device.resources.pairing_unknown
import earth.walkthru.opensensor.device.resources.perm_lan_body
import earth.walkthru.opensensor.device.resources.reboot_title
import earth.walkthru.opensensor.device.resources.sensor_batch
import earth.walkthru.opensensor.device.resources.sensor_check_model
import earth.walkthru.opensensor.device.resources.sensor_device_sticker
import earth.walkthru.opensensor.device.resources.sensor_estimate_explain
import earth.walkthru.opensensor.device.resources.sensor_estimate_none
import earth.walkthru.opensensor.device.resources.sensor_estimate_on
import earth.walkthru.opensensor.device.resources.sensor_estimate_state
import earth.walkthru.opensensor.device.resources.sensor_installed
import earth.walkthru.opensensor.device.resources.sensor_mismatch
import earth.walkthru.opensensor.device.resources.sensor_model
import earth.walkthru.opensensor.device.resources.sensor_model_supported
import earth.walkthru.opensensor.device.resources.sensor_model_unsupported
import earth.walkthru.opensensor.device.resources.sensor_or_enter
import earth.walkthru.opensensor.device.resources.sensor_phone_record
import earth.walkthru.opensensor.device.resources.sensor_records
import earth.walkthru.opensensor.device.resources.sensor_save
import earth.walkthru.opensensor.device.resources.sensor_scan
import earth.walkthru.opensensor.device.resources.sensor_sticker
import earth.walkthru.opensensor.device.resources.sensor_sticker_invalid
import earth.walkthru.opensensor.device.resources.sensor_store_estimate
import earth.walkthru.opensensor.device.resources.sensor_title
import earth.walkthru.opensensor.device.resources.sensor_vendor
import earth.walkthru.opensensor.device.resources.v1_body
import earth.walkthru.opensensor.device.resources.v1_title
import earth.walkthru.opensensor.device.resources.value_no
import earth.walkthru.opensensor.device.resources.value_not_set
import earth.walkthru.opensensor.device.resources.value_off
import earth.walkthru.opensensor.device.resources.value_unknown
import earth.walkthru.opensensor.device.resources.value_yes
import earth.walkthru.opensensor.device.resources.wifi_ap_detail
import earth.walkthru.opensensor.device.resources.wifi_connected
import earth.walkthru.opensensor.device.resources.wifi_connecting
import earth.walkthru.opensensor.device.resources.wifi_failed
import earth.walkthru.opensensor.device.resources.wifi_hide_passphrase
import earth.walkthru.opensensor.device.resources.wifi_ip
import earth.walkthru.opensensor.device.resources.wifi_join
import earth.walkthru.opensensor.device.resources.wifi_mac
import earth.walkthru.opensensor.device.resources.wifi_network
import earth.walkthru.opensensor.device.resources.wifi_networks_found
import earth.walkthru.opensensor.device.resources.wifi_no_name
import earth.walkthru.opensensor.device.resources.wifi_none_found
import earth.walkthru.opensensor.device.resources.wifi_off
import earth.walkthru.opensensor.device.resources.wifi_on_idle
import earth.walkthru.opensensor.device.resources.wifi_open
import earth.walkthru.opensensor.device.resources.wifi_open_network
import earth.walkthru.opensensor.device.resources.wifi_over_lan
import earth.walkthru.opensensor.device.resources.wifi_passphrase
import earth.walkthru.opensensor.device.resources.wifi_passphrase_label
import earth.walkthru.opensensor.device.resources.wifi_psk_open
import earth.walkthru.opensensor.device.resources.wifi_psk_storage
import earth.walkthru.opensensor.device.resources.wifi_psk_stored
import earth.walkthru.opensensor.device.resources.wifi_rssi
import earth.walkthru.opensensor.device.resources.wifi_save_connect
import earth.walkthru.opensensor.device.resources.wifi_scan
import earth.walkthru.opensensor.device.resources.wifi_scan_failed
import earth.walkthru.opensensor.device.resources.wifi_scan_unavailable
import earth.walkthru.opensensor.device.resources.wifi_scanning
import earth.walkthru.opensensor.device.resources.wifi_secured
import earth.walkthru.opensensor.device.resources.wifi_show_passphrase
import earth.walkthru.opensensor.device.resources.wifi_title
import earth.walkthru.opensensor.device.resources.wifi_unknown
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Shown for a value the device did not report. Never a made up default. */
internal const val NO_VALUE = "n/a"

/** The vendor the sticker card offers, a product name that stays as written in every language. */
internal const val VENDOR_PLANTOWER = "Plantower"

/** An example sticker for the field placeholder, a model and batch code, not copy. */
internal const val STICKER_EXAMPLE = "PMS5003T-202604081332"

/** Modules the card lists but cannot save yet. */
internal val UNSUPPORTED_MODELS = listOf("PMS5003", "PMSA003")

/** Where a sticker value came from, kept with the phone's record. */
const val STICKER_SOURCE_MANUAL = "manual"
const val STICKER_SOURCE_BARCODE = "barcode"

/**
 * Bluetooth pairing mode and passkey. Saving mode `none` and rebooting are confirmed first by the
 * controller. Pairing settings apply at the next boot, so the card says so and offers the reboot.
 */
@Composable
fun PairingCard(state: DeviceConfigUiState, onSave: (PairingChoice) -> Unit, onReboot: () -> Unit, modifier: Modifier = Modifier) {
    val snapshot = state.snapshot
    val ble = snapshot.config?.config?.ble
    val current = ble?.pairMode
    var selected by rememberSaveable(current) { mutableStateOf(current ?: PairMode.RANDOM) }
    // A secret, so it stays out of the saved instance state, like the Wi-Fi passphrase.
    var pin by remember { mutableStateOf("") }
    var showPin by remember { mutableStateOf(false) }

    BrandCard(modifier = modifier) {
        val title = stringResource(Res.string.pairing_title)
        CardHeader(
            title,
            info = InfoContent(
                title,
                stringResource(Res.string.info_pairing_body),
                stringResource(Res.string.pairing_random),
                stringResource(Res.string.pairing_fixed),
                stringResource(Res.string.pairing_none),
                stringResource(Res.string.pairing_next_boot),
            ),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusDot(if (current != null) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline)
            Text(
                when {
                    current != null -> stringResource(Res.string.pairing_mode, pairModeLabel(current))
                    snapshot.config == null -> stringResource(Res.string.config_not_read)
                    else -> stringResource(Res.string.pairing_unknown)
                },
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        if (ble != null && state.level.developer) {
            StatLine(
                stringResource(Res.string.pairing_display),
                when (ble.display) {
                    1 -> stringResource(Res.string.value_yes)
                    0 -> stringResource(Res.string.value_no)
                    else -> NO_VALUE
                },
            )
        }
        if (ble?.isPinDefault == true && current == PairMode.FIXED) {
            Spacer(Modifier.height(8.dp))
            WarningNote(stringResource(Res.string.pairing_default_pin, ConfigPatch.DEFAULT_PIN))
        }
        if (snapshot.config?.rebootRequired == true) {
            Spacer(Modifier.height(8.dp))
            WarningNote(stringResource(Res.string.pairing_reboot_pending))
        }
        Spacer(Modifier.height(10.dp))
        // One exclusive choice. The whole row is the radio button, the dot keeps its 48 dp slot.
        Column(Modifier.selectableGroup()) {
            PairMode.entries.forEach { mode ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = selected == mode, enabled = state.canEdit, role = Role.RadioButton) { selected = mode }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = selected == mode,
                        onClick = null,
                        enabled = state.canEdit,
                        modifier = Modifier.minimumInteractiveComponentSize(),
                    )
                    Text(pairModeLabel(mode), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (selected == PairMode.FIXED) {
            // Kept out of screen captures while the PIN is typed (#160). A recording on iOS forces the mask on.
            val captured = rememberCredentialCaptureGuard()
            // Leaving Fixed PIN or the screen drops the typed PIN and hides it again.
            DisposableEffect(Unit) {
                onDispose {
                    pin = ""
                    showPin = false
                }
            }
            val revealed = showPin && !captured
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = pin,
                onValueChange = { v -> if (v.length <= 6 && v.all { it.isDigit() }) pin = v },
                label = { Text(stringResource(if (current == PairMode.FIXED) Res.string.pairing_new_pin else Res.string.pairing_pin)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showPin = !showPin }, enabled = !captured) {
                        Icon(
                            if (revealed) AppIcons.VisibilityOff else AppIcons.Visibility,
                            contentDescription = stringResource(if (revealed) Res.string.pairing_hide_pin else Res.string.pairing_show_pin),
                        )
                    }
                },
                enabled = state.canEdit,
                modifier = Modifier.fillMaxWidth(),
                supportingText = if (pin.isNotEmpty() && pin.length != 6) ({ Text(stringResource(Res.string.pairing_six_digits)) }) else null,
                isError = pin.isNotEmpty() && pin.length != 6,
            )
        }
        Spacer(Modifier.height(10.dp))
        val pinOk = selected != PairMode.FIXED || pin.length == 6 || (pin.isEmpty() && current == PairMode.FIXED)
        val needsPin = selected == PairMode.FIXED && current != PairMode.FIXED && pin.length != 6
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinePillButton(
                stringResource(Res.string.pairing_save),
                onClick = {
                    onSave(PairingChoice(selected, pin.takeIf { it.length == 6 }))
                    pin = ""
                },
                enabled = state.canEdit && pinOk && !needsPin && (selected != current || pin.length == 6),
            )
            // Disabled during a transfer. A REBOOT would kill the download (see DeviceConfigUiState.canDisrupt).
            OutlinePillButton(stringResource(Res.string.reboot_title), onClick = onReboot, enabled = state.canDisrupt)
        }
    }
}

/** The short name of a pairing mode. Its explanation lives in the card's info sheet. */
@Composable
fun pairModeLabel(mode: PairMode): String = stringResource(
    when (mode) {
        PairMode.RANDOM -> Res.string.pairing_label_random
        PairMode.FIXED -> Res.string.pairing_label_fixed
        PairMode.NONE -> Res.string.pairing_label_none
    },
)

/** Phones bonded to the device, and the confirmed "forget all" action. */
@Composable
fun BondsCard(state: DeviceConfigUiState, onForgetAll: () -> Unit, modifier: Modifier = Modifier) {
    BrandCard(modifier = modifier) {
        val title = stringResource(Res.string.bonds_title)
        CardHeader(title, info = InfoContent(title, stringResource(Res.string.bonds_explain)))
        StatLine(stringResource(Res.string.bonds_count), state.snapshot.config?.config?.ble?.bonds?.toString() ?: NO_VALUE)
        Spacer(Modifier.height(10.dp))
        OutlinePillButton(stringResource(Res.string.bonds_forget), onClick = onForgetAll, enabled = state.canDisrupt)
    }
}

/** A protocol v1 device has no configuration surface. File sync works as before. */
@Composable
fun ProtocolV1Card(modifier: Modifier = Modifier) {
    BrandCard(modifier = modifier) {
        Eyebrow(stringResource(Res.string.v1_title), heading = true)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(Res.string.v1_body), style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Sync over the user's own Wi-Fi. Simple shows the server switch, whether the device is seen on
 * this network, whether this phone holds a token, and the token actions. Detailed adds the
 * explanations. Developer adds host, port, client count and the discovered address.
 */
@Composable
fun LanCard(
    state: DeviceConfigUiState,
    access: LanAccess,
    onRequestAccess: () -> Unit,
    onToggleLan: (Boolean) -> Unit,
    onFetchToken: () -> Unit,
    onRotateToken: () -> Unit,
    onReconnectBle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snapshot = state.snapshot
    val lan = snapshot.config?.config?.lan
    val hasToken = snapshot.hasLanToken
    val level = state.level

    BrandCard(modifier = modifier) {
        val title = stringResource(Res.string.lan_title)
        CardHeader(
            title,
            info = InfoContent(title, stringResource(Res.string.lan_explain), stringResource(Res.string.lan_token_explain), stringResource(Res.string.perm_lan_body)),
        ) {
            // The card title names the switch for screen readers.
            Switch(
                checked = lan?.isOn == true,
                onCheckedChange = onToggleLan,
                enabled = state.canEdit && lan != null,
                modifier = Modifier.semantics { contentDescription = title },
            )
        }
        if (level.detailed) {
            StatLine(
                stringResource(Res.string.lan_server),
                when (lan?.isOn) {
                    true -> stringResource(Res.string.lan_server_on)
                    false -> stringResource(Res.string.value_off)
                    null -> NO_VALUE
                },
            )
        }
        if (level.developer) {
            StatLine(stringResource(Res.string.lan_host), lan?.host?.let { "$it.local" } ?: NO_VALUE)
            StatLine(stringResource(Res.string.lan_port), lan?.port?.toString() ?: NO_VALUE)
            StatLine(stringResource(Res.string.lan_clients), lan?.clients?.toString() ?: NO_VALUE)
        }

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(AppIcons.Wifi, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Text(
                when {
                    !access.granted -> stringResource(Res.string.lan_no_access)
                    snapshot.lanSeen -> {
                        val endpoint = snapshot.lanEndpoint
                        if (level.developer && endpoint != null) {
                            stringResource(Res.string.lan_seen_at, endpoint)
                        } else {
                            stringResource(Res.string.lan_seen)
                        }
                    }
                    snapshot.lanDiscoveryError != null && level.detailed -> stringResource(Res.string.lan_discovery_error, snapshot.lanDiscoveryError)
                    else -> stringResource(Res.string.lan_not_seen)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (!access.granted && access.requestable) {
            Spacer(Modifier.height(6.dp))
            OutlinePillButton(stringResource(Res.string.lan_allow), onClick = onRequestAccess)
        }

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusDot(if (hasToken) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline)
            Text(stringResource(if (hasToken) Res.string.lan_token_held else Res.string.lan_token_missing), style = MaterialTheme.typography.bodyLarge)
        }
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.overBle) {
                OutlinePillButton(stringResource(if (hasToken) Res.string.lan_fetch_again else Res.string.lan_fetch), onClick = onFetchToken, enabled = state.canEdit)
            } else {
                OutlinePillButton(stringResource(Res.string.lan_reconnect_ble), onClick = onReconnectBle, enabled = state.ready && !snapshot.busy && !snapshot.heldInBackground)
            }
            if (level.detailed) OutlinePillButton(stringResource(Res.string.lan_rotate), onClick = onRotateToken, enabled = state.canDisrupt)
        }
        if (!state.overBle) InfoNote(stringResource(Res.string.lan_fetch_ble_only))
    }
}

/**
 * Wi-Fi on the device: state in words, scan, pick, passphrase, save and connect. The controller
 * polls the config while the device reports `connecting`. Detailed adds the IP address, Developer
 * the signal strength and MAC address. The passphrase is never echoed back.
 */
@Composable
fun WifiCard(
    state: DeviceConfigUiState,
    onToggleWifi: (Boolean) -> Unit,
    onScan: () -> Unit,
    onClearScan: () -> Unit,
    onJoin: (WifiJoin) -> Unit,
    modifier: Modifier = Modifier,
) {
    val snapshot = state.snapshot
    val wifi = snapshot.config?.config?.wifi
    val ws = wifi?.wifiState
    val level = state.level
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    var pickedOpen by rememberSaveable { mutableStateOf(false) }
    // Kept in memory only, never saved with the instance state, so it does not outlive the screen.
    var psk by remember { mutableStateOf("") }
    var showPsk by remember { mutableStateOf(false) }
    val scan = snapshot.wifiScan
    val ssid = wifi?.ssid ?: "?"

    BrandCard(modifier = modifier) {
        val title = stringResource(Res.string.wifi_title)
        CardHeader(title, info = InfoContent(title, stringResource(Res.string.info_wifi_body), stringResource(Res.string.wifi_psk_storage))) {
            // The card title names the switch for screen readers.
            Switch(
                checked = wifi?.isOn == true,
                onCheckedChange = onToggleWifi,
                enabled = state.canEdit && wifi != null,
                modifier = Modifier.semantics { contentDescription = title },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusDot(
                when (ws) {
                    WifiState.CONNECTED -> MaterialTheme.colorScheme.secondary
                    WifiState.CONNECTING -> MaterialTheme.colorScheme.tertiary
                    WifiState.FAILED -> MaterialTheme.colorScheme.error
                    WifiState.OFF, null -> MaterialTheme.colorScheme.outline
                },
            )
            Text(
                when (ws) {
                    WifiState.CONNECTED -> stringResource(Res.string.wifi_connected, ssid)
                    WifiState.CONNECTING -> stringResource(Res.string.wifi_connecting, ssid)
                    WifiState.FAILED -> stringResource(Res.string.wifi_failed, ssid)
                    WifiState.OFF -> stringResource(if (wifi.isOn) Res.string.wifi_on_idle else Res.string.wifi_off)
                    null -> stringResource(if (wifi == null) Res.string.config_not_read else Res.string.wifi_unknown)
                },
                style = MaterialTheme.typography.bodyLarge,
                // Changes from connecting to connected or failed while the user waits after a join.
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (ws == WifiState.CONNECTING) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (level.detailed) {
            Spacer(Modifier.height(6.dp))
            StatLine(stringResource(Res.string.wifi_network), wifi?.ssid?.ifEmpty { NO_VALUE } ?: NO_VALUE)
            StatLine(
                stringResource(Res.string.wifi_passphrase),
                when (wifi?.pskSet) {
                    1 -> stringResource(Res.string.wifi_psk_stored)
                    0 -> stringResource(Res.string.wifi_psk_open)
                    else -> NO_VALUE
                },
            )
            StatLine(stringResource(Res.string.wifi_ip), wifi?.ip?.ifEmpty { NO_VALUE } ?: NO_VALUE)
        }
        if (level.developer) {
            StatLine(stringResource(Res.string.wifi_rssi), wifi?.rssi?.takeIf { it != 0 }?.let { "$it dBm" } ?: NO_VALUE)
            StatLine(stringResource(Res.string.wifi_mac), wifi?.mac ?: NO_VALUE)
        }

        Spacer(Modifier.height(12.dp))
        OutlinePillButton(stringResource(Res.string.wifi_scan), onClick = onScan, enabled = state.canEdit && scan !is WifiScanState.Scanning)
        if (snapshot.transport == TransportKind.LAN) InfoNote(stringResource(Res.string.wifi_over_lan))

        when (scan) {
            WifiScanState.Idle -> Unit
            WifiScanState.Scanning -> {
                Spacer(Modifier.height(10.dp))
                Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(stringResource(Res.string.wifi_scanning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            is WifiScanState.Failed -> {
                Spacer(Modifier.height(10.dp))
                WarningNote(stringResource(if (scan.unavailable) Res.string.wifi_scan_unavailable else Res.string.wifi_scan_failed))
            }
            is WifiScanState.Done -> {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        pluralStringResource(Res.plurals.wifi_networks_found, scan.accessPoints.size, scan.accessPoints.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        onClearScan()
                        picked = null
                    }) { Text(stringResource(Res.string.action_hide)) }
                }
                if (scan.accessPoints.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PeacockIcon(PeacockExpression.CONFUSED, Modifier.size(48.dp))
                        Text(stringResource(Res.string.wifi_none_found), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    }
                }
                // One network at a time, a group of radio buttons for screen readers.
                Column(Modifier.selectableGroup()) {
                    scan.accessPoints.forEach { ap ->
                        AccessPointRow(ap, selected = picked == ap.ssid, showDetails = level.developer) {
                            picked = ap.ssid
                            pickedOpen = !ap.wifiAuth.secured
                            psk = ""
                        }
                    }
                }
            }
        }

        picked?.let { network ->
            Spacer(Modifier.height(12.dp))
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(Res.string.wifi_join, network), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                    if (pickedOpen) {
                        Text(stringResource(Res.string.wifi_open_network), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        val captured = rememberCredentialCaptureGuard()
                        // Leaving the join form or the page drops the passphrase and hides it again.
                        DisposableEffect(Unit) {
                            onDispose {
                                psk = ""
                                showPsk = false
                            }
                        }
                        val revealed = showPsk && !captured
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = psk,
                            onValueChange = { psk = it },
                            label = { Text(stringResource(Res.string.wifi_passphrase_label)) },
                            singleLine = true,
                            visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            trailingIcon = {
                                IconButton(onClick = { showPsk = !showPsk }, enabled = !captured) {
                                    Icon(
                                        if (revealed) AppIcons.VisibilityOff else AppIcons.Visibility,
                                        contentDescription = stringResource(if (revealed) Res.string.wifi_hide_passphrase else Res.string.wifi_show_passphrase),
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = state.canEdit,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PillButton(
                            stringResource(Res.string.wifi_save_connect),
                            onClick = {
                                onJoin(WifiJoin(network, psk, pickedOpen))
                                picked = null
                                psk = ""
                            },
                            enabled = state.canEdit && (pickedOpen || psk.encodeToByteArray().size in 8..63),
                        )
                        TextButton(onClick = {
                            picked = null
                            psk = ""
                        }) { Text(stringResource(Res.string.action_cancel)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccessPointRow(ap: Frame.WifiAp, selected: Boolean, showDetails: Boolean, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (selected) FloatingControl.Selected else MaterialTheme.colorScheme.surface,
        contentColor = if (selected) FloatingControl.OnSelected else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (ap.wifiAuth.secured) AppIcons.Lock else AppIcons.LockOpen,
                contentDescription = stringResource(if (ap.wifiAuth.secured) Res.string.wifi_secured else Res.string.wifi_open),
                tint = if (selected) FloatingControl.OnSelected else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(ap.ssid.ifEmpty { stringResource(Res.string.wifi_no_name) }, style = MaterialTheme.typography.bodyLarge)
                if (showDetails) {
                    Text(
                        stringResource(Res.string.wifi_ap_detail, ap.wifiAuth.label, ap.channel),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (selected) FloatingControl.OnSelected else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            SignalBars(ap.rssi, showDbm = showDetails, onSelected = selected)
        }
    }
}

/**
 * The fitted sensor module, for boards with `sensorIdentity`. Sticker entry is a physical
 * installation claim, and the board must acknowledge it before the phone keeps a record. Manual
 * entry works on both platforms. [onScan] opens a barcode scanner where the platform has one, and
 * its result arrives as [scan]. Null hides the scan button.
 */
@Composable
fun SensorIdentityCard(
    state: DeviceConfigUiState,
    scan: StickerScan?,
    onScan: (() -> Unit)?,
    onSave: (sticker: String, batchEstimate: Boolean, source: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val snapshot = state.snapshot
    val boardSensor = snapshot.config?.config?.sensor
    val record = snapshot.sensorRecord
    val level = state.level
    var vendorMenu by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    var serial by rememberSaveable(boardSensor?.serial) { mutableStateOf(boardSensor?.serial.orEmpty()) }
    var source by rememberSaveable(boardSensor?.serial) { mutableStateOf(STICKER_SOURCE_MANUAL) }
    var useBatch by rememberSaveable(boardSensor?.serial, boardSensor?.batchCandidate) { mutableStateOf(boardSensor?.batchCandidate == 1) }
    LaunchedEffect(scan?.id) {
        scan?.value?.let {
            serial = it.trim()
            source = STICKER_SOURCE_BARCODE
        }
    }
    val label = SensorLabel.parse(serial)
    val matchesInstalledModel = label?.model == DeviceConfigController.SUPPORTED_MODEL
    val knownBatch = matchesInstalledModel && label?.batch == PlantowerBatchCandidate.BATCH
    val effectiveBatch = useBatch && knownBatch
    val editable = state.canEdit && !snapshot.syncRunning

    BrandCard(modifier = modifier) {
        val title = stringResource(Res.string.sensor_title)
        CardHeader(
            title,
            info = InfoContent(
                title,
                stringResource(Res.string.sensor_check_model),
                stringResource(Res.string.sensor_estimate_explain, PlantowerBatchCandidate.FACTOR.toString()),
            ),
        )
        Text(stringResource(Res.string.sensor_installed), style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column {
                Text(stringResource(Res.string.sensor_vendor), style = MaterialTheme.typography.labelSmall)
                OutlinedButton(onClick = { vendorMenu = true }, shape = FloatingControl.SmallShape) { Text(VENDOR_PLANTOWER) }
                DropdownMenu(expanded = vendorMenu, onDismissRequest = { vendorMenu = false }) {
                    DropdownMenuItem(text = { Text(VENDOR_PLANTOWER) }, onClick = { vendorMenu = false })
                }
            }
            Column {
                Text(stringResource(Res.string.sensor_model), style = MaterialTheme.typography.labelSmall)
                OutlinedButton(onClick = { modelMenu = true }, shape = FloatingControl.SmallShape) { Text(DeviceConfigController.SUPPORTED_MODEL) }
                DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.sensor_model_supported, DeviceConfigController.SUPPORTED_MODEL)) },
                        onClick = { modelMenu = false },
                    )
                    UNSUPPORTED_MODELS.forEach { model ->
                        DropdownMenuItem(text = { Text(stringResource(Res.string.sensor_model_unsupported, model)) }, onClick = {}, enabled = false)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (onScan != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinePillButton(stringResource(Res.string.sensor_scan), onClick = onScan, enabled = editable)
                Text(stringResource(Res.string.sensor_or_enter), style = MaterialTheme.typography.bodySmall)
            }
        }
        scan?.error?.takeIf { scan.value == null }?.let { WarningNote(it) }
        OutlinedTextField(
            value = serial,
            onValueChange = {
                serial = it
                source = STICKER_SOURCE_MANUAL
            },
            label = { Text(stringResource(Res.string.sensor_sticker)) },
            placeholder = { Text(STICKER_EXAMPLE) },
            singleLine = true,
            enabled = editable,
            modifier = Modifier.fillMaxWidth(),
            isError = serial.isNotBlank() && !matchesInstalledModel,
        )
        if (label != null) {
            StatLine(stringResource(Res.string.sensor_batch), label.batch)
        } else if (serial.isNotBlank()) {
            WarningNote(stringResource(Res.string.sensor_sticker_invalid))
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.sensor_store_estimate), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(checked = effectiveBatch, onCheckedChange = { useBatch = it }, enabled = editable && knownBatch)
        }
        if (!knownBatch && label != null) InfoNote(stringResource(Res.string.sensor_estimate_none))
        Spacer(Modifier.height(10.dp))
        PillButton(
            stringResource(Res.string.sensor_save),
            onClick = { onSave(serial, effectiveBatch, source) },
            enabled = editable && matchesInstalledModel,
        )
        Spacer(Modifier.height(8.dp))
        StatLine(stringResource(Res.string.sensor_device_sticker), boardSensor?.serial ?: stringResource(Res.string.value_not_set))
        StatLine(
            stringResource(Res.string.sensor_estimate_state),
            stringResource(if (boardSensor?.batchCandidate == 1) Res.string.sensor_estimate_on else Res.string.value_off),
        )
        record?.let {
            if (level.detailed) {
                StatLine(stringResource(Res.string.sensor_phone_record), it.serial ?: stringResource(Res.string.value_unknown))
                StatLine(stringResource(Res.string.sensor_records), it.count.toString())
            }
            if (boardSensor != null && boardSensor.serial != it.serial) {
                WarningNote(stringResource(Res.string.sensor_mismatch))
            }
        }
    }
}
