package earth.walkthru.aqsync.ui.components

import android.content.Intent
import android.content.res.Resources
import android.provider.Settings
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import earth.walkthru.aqsync.R
import earth.walkthru.aqsync.data.ReconnectState
import earth.walkthru.aqsync.data.ble.BleConnectionFailure
import earth.walkthru.aqsync.data.ble.ConnectionState
import earth.walkthru.aqsync.data.ble.TransportKind
import earth.walkthru.opensensor.designsystem.AppIcons
import earth.walkthru.opensensor.designsystem.SectionTabs
import earth.walkthru.opensensor.designsystem.StatusDot
import earth.walkthru.opensensor.designsystem.TagChip
import earth.walkthru.opensensor.designsystem.TickingText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.Locale

/** Shown where a value is missing or not read yet. Never a long dash (copy rules). */
const val NO_VALUE = "n/a"

fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KiB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.2f MiB", bytes / 1048576.0)
    else -> String.format(Locale.US, "%.2f GiB", bytes / 1073741824.0)
}

fun formatRate(bytesPerSecond: Double): String = when {
    bytesPerSecond < 1024 -> String.format(Locale.US, "%.0f B/s", bytesPerSecond)
    else -> String.format(Locale.US, "%.1f KiB/s", bytesPerSecond / 1024.0)
}

fun formatAgo(ageMs: Long, res: Resources): String {
    val s = ageMs / 1000
    return when {
        ageMs < 0 -> res.getString(R.string.ago_just_now)
        s < 60 -> res.getString(R.string.ago_seconds, s)
        s < 3600 -> res.getString(R.string.ago_minutes, s / 60)
        s < 86_400 -> res.getString(R.string.ago_hours, s / 3600)
        else -> res.getString(R.string.ago_days, s / 86_400)
    }
}

/** [formatAgo] bound to the current resources, for leaf `TickingText` lambdas. */
@Composable
fun agoFormatter(): (Long) -> String {
    val res = LocalResources.current
    return remember(res) { { ageMs -> formatAgo(ageMs, res) } }
}

fun formatUptime(seconds: Long): String {
    val d = seconds / 86_400
    val h = (seconds % 86_400) / 3600
    val m = (seconds % 3600) / 60
    return when {
        d > 0 -> "${d}d ${h}h"
        h > 0 -> "${h}h ${m}m"
        else -> "${m}m ${seconds % 60}s"
    }
}

/**
 * Collect one-off UI events only while the screen is at least STARTED: a snackbar or a system dialog
 * is never fired at a stopped activity. Events are buffered by the producer's `extraBufferCapacity`.
 */
@Composable
fun <T> CollectEvents(events: Flow<T>, onEvent: suspend (T) -> Unit) {
    val owner = LocalLifecycleOwner.current
    val latest by rememberUpdatedState(onEvent)
    LaunchedEffect(events, owner) {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            withContext(Dispatchers.Main.immediate) { events.collect { latest(it) } }
        }
    }
}

/**
 * Human description of the link state, with the pairing hint where it matters. Link internals such
 * as the negotiated MTU appear only when [technical] is set (Developer level).
 */
@Composable
fun ConnectionState.describe(technical: Boolean = false): String = when (this) {
    ConnectionState.Disconnected -> stringResource(R.string.conn_disconnected)
    ConnectionState.Connecting -> stringResource(R.string.conn_connecting)
    ConnectionState.DiscoveringServices -> stringResource(if (technical) R.string.conn_discovering else R.string.conn_connecting)
    ConnectionState.Bonding -> stringResource(R.string.conn_pairing)
    ConnectionState.NegotiatingMtu -> stringResource(if (technical) R.string.conn_mtu else R.string.conn_setting_up)
    ConnectionState.EnablingNotifications -> stringResource(if (technical) R.string.conn_notifications else R.string.conn_setting_up)
    ConnectionState.Authenticating -> stringResource(if (technical) R.string.conn_lan_auth else R.string.conn_lan_checking)
    is ConnectionState.Ready -> if (technical) stringResource(R.string.conn_ready_mtu, mtu) else stringResource(R.string.conn_ready)
    // A pairing repair message is plain language and says what to do, so every level shows it.
    is ConnectionState.Failed -> when {
        BleConnectionFailure.needsPairingRepair(this) -> stringResource(R.string.conn_pairing_repair)
        technical -> reason
        else -> stringResource(R.string.conn_failed)
    }
}

@Composable
fun ConnectionBanner(
    state: ConnectionState,
    modifier: Modifier = Modifier,
    reconnecting: ReconnectState? = null,
    technical: Boolean = false,
) {
    val context = LocalContext.current
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
                    }
                )
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(state.describe(technical), style = MaterialTheme.typography.bodyMedium)
                if (reconnecting != null) {
                    Text(
                        if (technical) {
                            stringResource(R.string.conn_link_lost_detail, reconnecting.reason, reconnecting.attempt, reconnecting.maxAttempts)
                        } else {
                            stringResource(R.string.conn_link_lost)
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (BleConnectionFailure.needsPairingRepair(state)) {
                    TextButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }) {
                        Text(stringResource(R.string.conn_bluetooth_settings))
                    }
                }
            }
        }
    }
}

enum class DeviceTab { TODAY, HISTORY, FILES, DEVICE }

/**
 * The four sections of one device, as a tab row under the screen's top bar. The app's bottom bar
 * stays below with My devices selected, so a device never draws a second bottom bar.
 */
@Composable
fun DeviceSectionTabs(
    selected: DeviceTab,
    containerColor: Color = MaterialTheme.colorScheme.background,
    onSelect: (DeviceTab) -> Unit,
) {
    SectionTabs(
        labels = DeviceTab.entries.map { tab ->
            stringResource(
                when (tab) {
                    DeviceTab.TODAY -> R.string.tab_today
                    DeviceTab.HISTORY -> R.string.tab_history
                    DeviceTab.FILES -> R.string.tab_files
                    DeviceTab.DEVICE -> R.string.tab_device
                },
            )
        },
        selectedIndex = selected.ordinal,
        onSelect = { onSelect(DeviceTab.entries[it]) },
        containerColor = containerColor,
    )
}

/** The name of a byte pipe, for chips and stat lines. */
@Composable
fun TransportKind.labelText(): String = stringResource(
    when (this) {
        TransportKind.BLE -> R.string.transport_ble
        TransportKind.LAN -> R.string.transport_lan
        TransportKind.FAKE -> R.string.transport_fake
    },
)

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
