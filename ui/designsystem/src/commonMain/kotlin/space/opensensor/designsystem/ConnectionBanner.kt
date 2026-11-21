package space.opensensor.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import space.opensensor.data.ble.ConnectionState
import space.opensensor.designsystem.resources.Res
import space.opensensor.designsystem.resources.conn_bluetooth_settings
import space.opensensor.designsystem.resources.conn_discovering
import space.opensensor.designsystem.resources.conn_held_background
import space.opensensor.designsystem.resources.conn_lan_auth
import space.opensensor.designsystem.resources.conn_lan_checking
import space.opensensor.designsystem.resources.conn_link_lost_detail
import space.opensensor.designsystem.resources.conn_mtu
import space.opensensor.designsystem.resources.conn_notifications
import space.opensensor.designsystem.resources.conn_ready_mtu
import space.opensensor.designsystem.resources.conn_setting_up
import space.opensensor.strings.resources.conn_connecting
import space.opensensor.strings.resources.conn_disconnected
import space.opensensor.strings.resources.conn_failed
import space.opensensor.strings.resources.conn_link_lost
import space.opensensor.strings.resources.conn_pairing
import space.opensensor.strings.resources.conn_pairing_repair
import space.opensensor.strings.resources.conn_ready
import space.opensensor.strings.resources.Res as SharedRes

/** A link that was Ready dropped on its own and the platform is trying to get it back. */
data class LinkRetry(val attempt: Int, val maxAttempts: Int, val reason: String)

/**
 * The link state as the status strip every page of one sensor shows on both phones, beside the
 * transport chip of [ThingTopBar]. It names the reconnect attempt and, when pairing broke, links to
 * the Bluetooth settings. It is a strip in the state colours, not a content card.
 */
@Composable
fun ConnectionBanner(
    state: ConnectionState,
    reconnecting: LinkRetry?,
    technical: Boolean,
    pairingRepair: Boolean,
    onOpenBluetoothSettings: () -> Unit,
    modifier: Modifier = Modifier,
    heldInBackground: Boolean = false,
) {
    val (container, content) = when {
        heldInBackground -> MaterialTheme.colorScheme.surfaceContainer to MaterialTheme.colorScheme.onSurface
        else -> when (state) {
            is ConnectionState.Ready -> MaterialTheme.colorScheme.surfaceContainer to MaterialTheme.colorScheme.onSurface
            is ConnectionState.Failed -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
            ConnectionState.Bonding -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
            else -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        }
    }
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.large, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (heldInBackground || state.isBusy) {
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
                    if (heldInBackground) stringResource(Res.string.conn_held_background) else state.describe(technical, pairingRepair),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                if (reconnecting != null && !heldInBackground) {
                    Text(
                        if (technical) {
                            stringResource(Res.string.conn_link_lost_detail, reconnecting.reason, reconnecting.attempt, reconnecting.maxAttempts)
                        } else {
                            stringResource(SharedRes.string.conn_link_lost)
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

/**
 * The link state in words. Link internals such as the MTU appear only when [technical] is set
 * (Developer level). [pairingRepair] comes from the platform, which knows its own failure codes.
 */
@Composable
fun ConnectionState.describe(technical: Boolean, pairingRepair: Boolean = false): String = when (this) {
    ConnectionState.Disconnected -> stringResource(SharedRes.string.conn_disconnected)
    ConnectionState.Connecting -> stringResource(SharedRes.string.conn_connecting)
    ConnectionState.DiscoveringServices -> if (technical) stringResource(Res.string.conn_discovering) else stringResource(SharedRes.string.conn_connecting)
    ConnectionState.Bonding -> stringResource(SharedRes.string.conn_pairing)
    ConnectionState.NegotiatingMtu -> stringResource(if (technical) Res.string.conn_mtu else Res.string.conn_setting_up)
    ConnectionState.EnablingNotifications -> stringResource(if (technical) Res.string.conn_notifications else Res.string.conn_setting_up)
    ConnectionState.Authenticating -> stringResource(if (technical) Res.string.conn_lan_auth else Res.string.conn_lan_checking)
    // iOS reports Ready without a negotiated MTU (0), so the technical line only names it when known.
    is ConnectionState.Ready -> if (technical && mtu > 0) stringResource(Res.string.conn_ready_mtu, mtu) else stringResource(SharedRes.string.conn_ready)
    // A pairing repair message is plain language and says what to do, so every level shows it.
    is ConnectionState.Failed -> when {
        pairingRepair -> stringResource(SharedRes.string.conn_pairing_repair)
        technical -> reason
        else -> stringResource(SharedRes.string.conn_failed)
    }
}
