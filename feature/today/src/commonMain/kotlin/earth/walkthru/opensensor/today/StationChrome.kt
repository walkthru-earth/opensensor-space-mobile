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
import earth.walkthru.opensensor.today.resources.conn_held_background
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
import earth.walkthru.opensensor.today.resources.tab_device
import earth.walkthru.opensensor.today.resources.tab_files
import earth.walkthru.opensensor.today.resources.tab_history
import earth.walkthru.opensensor.today.resources.tab_today
import earth.walkthru.opensensor.today.resources.today_station_short
import earth.walkthru.opensensor.today.resources.today_title
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

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
