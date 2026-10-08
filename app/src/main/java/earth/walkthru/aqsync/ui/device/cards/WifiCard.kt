package earth.walkthru.aqsync.ui.device.cards

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import earth.walkthru.aqsync.R
import earth.walkthru.aqsync.data.WifiScanState
import earth.walkthru.aqsync.data.ble.TransportKind
import earth.walkthru.aqsync.data.protocol.Frame
import earth.walkthru.aqsync.data.protocol.WifiState
import earth.walkthru.aqsync.ui.components.NO_VALUE
import earth.walkthru.aqsync.ui.device.DeviceUiState
import earth.walkthru.opensensor.designsystem.AppIcons
import earth.walkthru.opensensor.designsystem.BrandCard
import earth.walkthru.opensensor.designsystem.CardHeader
import earth.walkthru.opensensor.designsystem.FloatingControl
import earth.walkthru.opensensor.designsystem.InfoContent
import earth.walkthru.opensensor.designsystem.InfoNote
import earth.walkthru.opensensor.designsystem.OutlinePillButton
import earth.walkthru.opensensor.designsystem.PillButton
import earth.walkthru.opensensor.designsystem.SignalBars
import earth.walkthru.opensensor.designsystem.StatLine
import earth.walkthru.opensensor.designsystem.StatusDot
import earth.walkthru.opensensor.designsystem.WarningNote

/**
 * Wi-Fi on the device: state in words, scan, pick, passphrase, save and connect. The ViewModel
 * polls the config while the device reports `connecting`. Detailed adds the IP address, Developer
 * the signal strength and MAC address. The passphrase is never echoed back.
 */
@Composable
fun WifiCard(
    state: DeviceUiState,
    onToggleWifi: (Boolean) -> Unit,
    onScan: () -> Unit,
    onClearScan: () -> Unit,
    onJoin: (ssid: String, psk: String, open: Boolean) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val wifi = state.config?.config?.wifi
    val ws = wifi?.wifiState
    val level = state.detailLevel
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    var pickedOpen by rememberSaveable { mutableStateOf(false) }
    var psk by rememberSaveable { mutableStateOf("") }
    var showPsk by rememberSaveable { mutableStateOf(false) }
    val scan = state.wifiScan
    val ssid = wifi?.ssid ?: "?"

    BrandCard(modifier = modifier) {
        val title = stringResource(R.string.wifi_title)
        CardHeader(title, info = InfoContent(title, stringResource(R.string.info_wifi_body), stringResource(R.string.wifi_psk_storage))) {
            Switch(checked = wifi?.isOn == true, onCheckedChange = onToggleWifi, enabled = state.canEdit && wifi != null)
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
                    WifiState.CONNECTED -> stringResource(R.string.wifi_connected, ssid)
                    WifiState.CONNECTING -> stringResource(R.string.wifi_connecting, ssid)
                    WifiState.FAILED -> stringResource(R.string.wifi_failed, ssid)
                    WifiState.OFF -> stringResource(if (wifi.isOn) R.string.wifi_on_idle else R.string.wifi_off)
                    null -> stringResource(if (wifi == null) R.string.config_not_read else R.string.wifi_unknown)
                },
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        if (ws == WifiState.CONNECTING) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (level.detailed) {
            Spacer(Modifier.height(6.dp))
            StatLine(stringResource(R.string.wifi_network), wifi?.ssid?.ifEmpty { NO_VALUE } ?: NO_VALUE)
            StatLine(
                stringResource(R.string.wifi_passphrase),
                when (wifi?.pskSet) {
                    1 -> stringResource(R.string.wifi_psk_stored)
                    0 -> stringResource(R.string.wifi_psk_open)
                    else -> NO_VALUE
                },
            )
            StatLine(stringResource(R.string.wifi_ip), wifi?.ip?.ifEmpty { NO_VALUE } ?: NO_VALUE)
        }
        if (level.developer) {
            StatLine(stringResource(R.string.wifi_rssi), wifi?.rssi?.takeIf { it != 0 }?.let { "$it dBm" } ?: NO_VALUE)
            StatLine(stringResource(R.string.wifi_mac), wifi?.mac ?: NO_VALUE)
        }

        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinePillButton(stringResource(R.string.wifi_scan), onClick = onScan, enabled = state.canEdit && scan !is WifiScanState.Scanning)
            OutlinePillButton(stringResource(R.string.wifi_refresh), onClick = onRefresh, enabled = state.ready && state.isV2)
        }
        if (state.transport == TransportKind.LAN) InfoNote(stringResource(R.string.wifi_over_lan))

        when (scan) {
            WifiScanState.Idle -> Unit
            WifiScanState.Scanning -> {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.wifi_scanning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            is WifiScanState.Failed -> {
                Spacer(Modifier.height(10.dp))
                WarningNote(stringResource(if (scan.unavailable) R.string.wifi_scan_unavailable else R.string.wifi_scan_failed))
            }
            is WifiScanState.Done -> {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        pluralStringResource(R.plurals.wifi_networks_found, scan.accessPoints.size, scan.accessPoints.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onClearScan(); picked = null }) { Text(stringResource(R.string.action_hide)) }
                }
                if (scan.accessPoints.isEmpty()) Text(stringResource(R.string.wifi_none_found), style = MaterialTheme.typography.bodySmall)
                scan.accessPoints.forEach { ap ->
                    AccessPointRow(ap, selected = picked == ap.ssid, showDetails = level.developer) {
                        picked = ap.ssid
                        pickedOpen = !ap.wifiAuth.secured
                        psk = ""
                    }
                }
            }
        }

        picked?.let { network ->
            Spacer(Modifier.height(12.dp))
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.wifi_join, network), style = MaterialTheme.typography.titleSmall)
                    if (pickedOpen) {
                        Text(stringResource(R.string.wifi_open_network), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = psk,
                            onValueChange = { psk = it },
                            label = { Text(stringResource(R.string.wifi_passphrase_label)) },
                            singleLine = true,
                            visualTransformation = if (showPsk) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            trailingIcon = {
                                IconButton(onClick = { showPsk = !showPsk }) {
                                    Icon(
                                        if (showPsk) AppIcons.VisibilityOff else AppIcons.Visibility,
                                        contentDescription = stringResource(if (showPsk) R.string.wifi_hide_passphrase else R.string.wifi_show_passphrase),
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
                            stringResource(R.string.wifi_save_connect),
                            onClick = { onJoin(network, psk, pickedOpen); picked = null; psk = "" },
                            enabled = state.canEdit && (pickedOpen || psk.toByteArray().size in 8..63),
                        )
                        TextButton(onClick = { picked = null; psk = "" }) { Text(stringResource(R.string.action_cancel)) }
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
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (ap.wifiAuth.secured) AppIcons.Lock else AppIcons.LockOpen,
                contentDescription = stringResource(if (ap.wifiAuth.secured) R.string.wifi_secured else R.string.wifi_open),
                tint = if (selected) FloatingControl.OnSelected else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(ap.ssid.ifEmpty { stringResource(R.string.wifi_no_name) }, style = MaterialTheme.typography.bodyLarge)
                if (showDetails) {
                    Text(stringResource(R.string.wifi_ap_detail, ap.wifiAuth.label, ap.channel), style = MaterialTheme.typography.bodySmall, color = if (selected) FloatingControl.OnSelected else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            SignalBars(ap.rssi, showDbm = showDetails, onSelected = selected)
        }
    }
}
