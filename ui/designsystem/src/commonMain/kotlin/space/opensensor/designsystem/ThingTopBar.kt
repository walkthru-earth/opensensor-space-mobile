package space.opensensor.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import space.opensensor.data.ble.TransportKind
import space.opensensor.designsystem.resources.Res
import space.opensensor.designsystem.resources.transport_ble
import space.opensensor.designsystem.resources.transport_fake
import space.opensensor.designsystem.resources.transport_lan

// The chrome every page of one sensor shares on both phones. One top bar with one colour, and the
// transport chip in its subtitle row, so moving between the pages of a sensor never changes the frame.

/**
 * The top bar of a Thing page (Station, Files, the file inspector, Device settings and Debug).
 * [title] is the page heading, [transport] shows as a [TransportChip] at the start of the subtitle row
 * when set, and [subtitle] follows it. [onBack] null leaves out the back arrow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThingTopBar(
    title: @Composable () -> Unit,
    onBack: (() -> Unit)?,
    backLabel: String,
    modifier: Modifier = Modifier,
    transport: TransportKind? = null,
    subtitle: (@Composable RowScope.() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        modifier = modifier,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(AppIcons.ArrowBack, contentDescription = backLabel) }
            }
        },
        title = {
            Column {
                title()
                if (transport != null || subtitle != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TransportChip(transport)
                        subtitle?.invoke(this)
                    }
                }
            }
        },
        actions = actions,
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

/** The name of a byte pipe, for chips and stat lines. */
@Composable
fun TransportKind.labelText(): String = stringResource(
    when (this) {
        TransportKind.BLE -> Res.string.transport_ble
        TransportKind.LAN -> Res.string.transport_lan
        TransportKind.FAKE -> Res.string.transport_fake
    },
)
