package earth.walkthru.aqsync.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import earth.walkthru.aqsync.data.ble.ConnectionState
import kotlinx.coroutines.delay
import java.util.Locale

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

fun formatAgo(ageMs: Long): String {
    val s = ageMs / 1000
    return when {
        ageMs < 0 -> "just now"
        s < 60 -> "$s s ago"
        s < 3600 -> "${s / 60} min ago"
        s < 86_400 -> "${s / 3600} h ago"
        else -> "${s / 86_400} d ago"
    }
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

/** A clock that ticks once a second so "updated N s ago" stays honest. */
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

/** Small caps label used above readings and in stat rows. */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text.uppercase(),
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

/** Human description of the link state, with the pairing hint where it matters. */
fun ConnectionState.describe(): String = when (this) {
    ConnectionState.Disconnected -> "Not connected"
    ConnectionState.Connecting -> "Connecting…"
    ConnectionState.DiscoveringServices -> "Discovering services…"
    ConnectionState.Bonding -> "Pairing — enter the 6-digit code shown on the device"
    ConnectionState.NegotiatingMtu -> "Negotiating MTU…"
    ConnectionState.EnablingNotifications -> "Enabling notifications…"
    ConnectionState.Authenticating -> "Authenticating on the LAN…"
    is ConnectionState.Ready -> "Connected · MTU $mtu"
    is ConnectionState.Failed -> "Failed: $reason"
}

@Composable
fun ConnectionBanner(state: ConnectionState, modifier: Modifier = Modifier) {
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
                Text(state.describe(), style = MaterialTheme.typography.bodyMedium)
                if (state == ConnectionState.Bonding) {
                    Text(
                        "The CoreS3 shows a passkey on its screen. Android will ask you to type it.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

enum class DeviceTab { TODAY, FILES }

@Composable
fun DeviceBottomBar(selected: DeviceTab, onSelect: (DeviceTab) -> Unit) {
    NavigationBar {
        NavigationBarItem(
            selected = selected == DeviceTab.TODAY,
            onClick = { onSelect(DeviceTab.TODAY) },
            icon = { Icon(Icons.Rounded.Sensors, contentDescription = null) },
            label = { Text("Today") },
        )
        NavigationBarItem(
            selected = selected == DeviceTab.FILES,
            onClick = { onSelect(DeviceTab.FILES) },
            icon = { Icon(Icons.Rounded.FolderOpen, contentDescription = null) },
            label = { Text("Files") },
        )
    }
}

/** Two-column key/value line for compact stat rows. */
@Composable
fun StatLine(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor)
    }
}
