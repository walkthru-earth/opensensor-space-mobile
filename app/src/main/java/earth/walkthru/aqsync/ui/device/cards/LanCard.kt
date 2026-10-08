package earth.walkthru.aqsync.ui.device.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import earth.walkthru.aqsync.R
import earth.walkthru.aqsync.ui.components.NO_VALUE
import earth.walkthru.aqsync.ui.device.DeviceUiState
import earth.walkthru.opensensor.designsystem.AppIcons
import earth.walkthru.opensensor.designsystem.BrandCard
import earth.walkthru.opensensor.designsystem.CardHeader
import earth.walkthru.opensensor.designsystem.InfoContent
import earth.walkthru.opensensor.designsystem.InfoNote
import earth.walkthru.opensensor.designsystem.OutlinePillButton
import earth.walkthru.opensensor.designsystem.StatLine
import earth.walkthru.opensensor.designsystem.StatusDot

/** What the card needs from the platform's local-network permission (API 37+, always granted below). */
data class LanAccess(val granted: Boolean, val requestable: Boolean)

/**
 * Sync over the user's own Wi-Fi. Simple shows the server switch, whether the device is seen on
 * this network, whether this phone holds a token, and the token actions. Detailed adds the
 * explanations. Developer adds host, port, client count and the discovered address.
 */
@Composable
fun LanCard(
    state: DeviceUiState,
    access: LanAccess,
    onRequestAccess: () -> Unit,
    onToggleLan: (Boolean) -> Unit,
    onFetchToken: () -> Unit,
    onRotateToken: () -> Unit,
    onReconnectBle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lan = state.config?.config?.lan
    val hasToken = state.known?.hasLanToken == true
    val level = state.detailLevel

    BrandCard(modifier = modifier) {
        val title = stringResource(R.string.lan_title)
        CardHeader(
            title,
            info = InfoContent(title, stringResource(R.string.lan_explain), stringResource(R.string.lan_token_explain), stringResource(R.string.perm_lan_body)),
        ) {
            Switch(checked = lan?.isOn == true, onCheckedChange = onToggleLan, enabled = state.canEdit && lan != null)
        }
        if (level.detailed) {
            StatLine(
                stringResource(R.string.lan_server),
                when (lan?.isOn) {
                    true -> stringResource(R.string.lan_server_on)
                    false -> stringResource(R.string.value_off)
                    null -> NO_VALUE
                },
            )
        }
        if (level.developer) {
            StatLine(stringResource(R.string.lan_host), lan?.host?.let { "$it.local" } ?: NO_VALUE)
            StatLine(stringResource(R.string.lan_port), lan?.port?.toString() ?: NO_VALUE)
            StatLine(stringResource(R.string.lan_clients), lan?.clients?.toString() ?: NO_VALUE)
        }

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(AppIcons.Wifi, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Text(
                when {
                    !access.granted -> stringResource(R.string.lan_no_access)
                    state.onLan != null -> if (level.developer) {
                        stringResource(R.string.lan_seen_at, "${state.onLan.host?.hostAddress}:${state.onLan.port}")
                    } else {
                        stringResource(R.string.lan_seen)
                    }
                    state.lanDiscoveryError != null && level.detailed -> stringResource(R.string.lan_discovery_error, state.lanDiscoveryError)
                    else -> stringResource(R.string.lan_not_seen)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (!access.granted && access.requestable) {
            Spacer(Modifier.height(6.dp))
            OutlinePillButton(stringResource(R.string.lan_allow), onClick = onRequestAccess)
        }

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusDot(if (hasToken) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline)
            Text(stringResource(if (hasToken) R.string.lan_token_held else R.string.lan_token_missing), style = MaterialTheme.typography.bodyLarge)
        }
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.overBle) {
                OutlinePillButton(stringResource(if (hasToken) R.string.lan_fetch_again else R.string.lan_fetch), onClick = onFetchToken, enabled = state.canEdit)
            } else {
                OutlinePillButton(stringResource(R.string.lan_reconnect_ble), onClick = onReconnectBle, enabled = state.ready && !state.busy)
            }
            if (level.detailed) OutlinePillButton(stringResource(R.string.lan_rotate), onClick = onRotateToken, enabled = state.canDisrupt)
        }
        if (!state.overBle) InfoNote(stringResource(R.string.lan_fetch_ble_only))
    }
}
