package space.opensensor.today

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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import space.opensensor.data.ble.ConnectionState
import space.opensensor.data.ble.TransportKind
import space.opensensor.designsystem.AppIcons
import space.opensensor.designsystem.SectionTabs
import space.opensensor.designsystem.StatusDot
import space.opensensor.designsystem.TagChip
import space.opensensor.today.resources.Res
import space.opensensor.today.resources.conn_bluetooth_settings
import space.opensensor.today.resources.conn_held_background
import space.opensensor.today.resources.conn_link_lost
import space.opensensor.today.resources.conn_link_lost_detail
import space.opensensor.today.resources.tab_device
import space.opensensor.today.resources.tab_files
import space.opensensor.today.resources.tab_history
import space.opensensor.today.resources.tab_today

// The station chrome every section shares on both platforms: the link banner, the section tabs and
// the transport chip. Android History, Files and Device and the iOS pages draw these, never copies.

/** The link state as a banner, with the reconnect attempt and, when pairing broke, a Bluetooth settings link. */
@Composable
fun ConnectionBanner(
    state: ConnectionState,
    reconnecting: TodayReconnect?,
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

/** The four sections of one station. */
enum class DeviceTab { TODAY, HISTORY, FILES, DEVICE }

/**
 * The four sections of one station, as a tab row under the screen's top bar. The bottom bar stays
 * below with My devices selected, so a station never draws a second bottom bar.
 */
@Composable
fun DeviceSectionTabs(selected: DeviceTab, containerColor: Color = MaterialTheme.colorScheme.background, onSelect: (DeviceTab) -> Unit) {
    SectionTabs(
        labels = DeviceTab.entries.map { tab ->
            stringResource(
                when (tab) {
                    DeviceTab.TODAY -> Res.string.tab_today
                    DeviceTab.HISTORY -> Res.string.tab_history
                    DeviceTab.FILES -> Res.string.tab_files
                    DeviceTab.DEVICE -> Res.string.tab_device
                },
            )
        },
        selectedIndex = selected.ordinal,
        onSelect = { onSelect(DeviceTab.entries[it]) },
        containerColor = containerColor,
    )
}

/** Small chip naming the active byte pipe, Bluetooth or Wi-Fi LAN (and the fake device in debug builds). */
@Composable
fun TransportChip(kind: TransportKind?, modifier: Modifier = Modifier) {
    if (kind == null) return
    val (icon, container, content) = when (kind) {
        TransportKind.BLE -> Triple(AppIcons.Bluetooth, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        TransportKind.LAN -> Triple(AppIcons.Wifi, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
        TransportKind.FAKE -> Triple(AppIcons.BugReport, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
    }
    TagChip(icon, kind.labelText(), container, content, modifier)
}
