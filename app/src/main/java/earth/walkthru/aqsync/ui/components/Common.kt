package earth.walkthru.aqsync.ui.components

import android.content.Intent
import android.content.res.Resources
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import earth.walkthru.aqsync.R
import earth.walkthru.aqsync.data.ReconnectState
import earth.walkthru.aqsync.data.ble.BleConnectionFailure
import earth.walkthru.aqsync.data.ble.ConnectionState
import earth.walkthru.aqsync.data.ble.TransportKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
 * A clock that ticks once a second so "updated N s ago" stays honest. Read it only inside the leaf
 * that prints the age (see [TickingText]): a read at screen level recomposes the whole screen at
 * 1 Hz.
 */
@Composable
fun rememberNowMillis(periodMs: Long = 1000): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(periodMs) {
        while (true) {
            delay(periodMs)
            now = System.currentTimeMillis()
        }
    }
    return now
}

/** A leaf text that re-renders once a second with the current time, so its parent does not. */
@Composable
fun TickingText(
    text: (nowMs: Long) -> String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.labelSmall,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    maxLines: Int = 1,
) {
    val now = rememberNowMillis()
    Text(text(now), modifier = modifier, style = style, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
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
 * Small caps label used above readings and in stat rows. Pass [uppercase] = false when the text
 * carries a unit: `"µm".uppercase()` is `"ΜM"` (Greek capital mu), which is how "(MM)" once
 * appeared on the particles card.
 */
@Composable
fun Eyebrow(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    uppercase: Boolean = true,
) {
    Text(
        if (uppercase) text.uppercase() else text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = modifier,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier, size: Int = 10) {
    Spacer(
        modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(color)
    )
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
    PrimaryTabRow(selectedTabIndex = selected.ordinal, containerColor = containerColor) {
        DeviceTab.entries.forEach { tab ->
            Tab(
                selected = tab == selected,
                onClick = { if (tab != selected) onSelect(tab) },
                text = {
                    Text(
                        stringResource(
                            when (tab) {
                                DeviceTab.TODAY -> R.string.tab_today
                                DeviceTab.HISTORY -> R.string.tab_history
                                DeviceTab.FILES -> R.string.tab_files
                                DeviceTab.DEVICE -> R.string.tab_device
                            },
                        ),
                        maxLines = 1,
                    )
                },
            )
        }
    }
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
        TransportKind.BLE -> Triple(Icons.Rounded.Bluetooth, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        TransportKind.LAN -> Triple(Icons.Rounded.Wifi, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
        TransportKind.FAKE -> Triple(Icons.Rounded.BugReport, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Surface(shape = MaterialTheme.shapes.small, color = container, contentColor = content, modifier = modifier) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
            Text(kind.labelText(), style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** Card wrapper used by the Device and Debug screens. */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Eyebrow(title)
                Spacer(Modifier.weight(1f))
                trailing?.invoke()
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

/** A warning strip inside a card. */
@Composable
fun WarningNote(text: String, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Rounded.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(20.dp))
            Text(text, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** An informational strip inside a card. */
@Composable
fun InfoNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(top = 6.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Two-column key/value line for compact stat rows. */
@Composable
fun StatLine(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor)
    }
}
