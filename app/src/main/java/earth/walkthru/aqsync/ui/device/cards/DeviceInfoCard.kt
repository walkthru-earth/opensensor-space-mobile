package earth.walkthru.aqsync.ui.device.cards

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import earth.walkthru.aqsync.R
import earth.walkthru.aqsync.data.ble.ConnectionState
import earth.walkthru.aqsync.data.ble.TransportKind
import earth.walkthru.aqsync.data.protocol.DeviceInfo
import earth.walkthru.aqsync.data.settings.DetailLevel
import earth.walkthru.aqsync.ui.components.BrandCard
import earth.walkthru.aqsync.ui.components.Eyebrow
import earth.walkthru.aqsync.ui.components.StatLine
import earth.walkthru.aqsync.ui.components.TickingText
import earth.walkthru.aqsync.ui.components.describe
import earth.walkthru.aqsync.ui.components.formatAgo

/**
 * The device header card. Simple names the device and how it is connected, in words. Detailed adds
 * firmware and protocol. Developer adds every identifier the firmware reports (schema, station,
 * device and boot IDs, dictionary hash, read window, payload size and endpoint).
 */
@Composable
fun DeviceInfoCard(
    name: String?,
    info: DeviceInfo?,
    connection: ConnectionState,
    transport: TransportKind?,
    endpoint: String?,
    configReadAtEpochMs: Long?,
    level: DetailLevel,
    modifier: Modifier = Modifier,
) {
    BrandCard(modifier = modifier) {
        Eyebrow(stringResource(R.string.device_title))
        Spacer(Modifier.height(6.dp))
        Text(name ?: stringResource(R.string.device_unnamed), style = MaterialTheme.typography.titleLarge)
        Text(
            if (connection.isReady && transport != null) stringResource(R.string.device_connected_over, transport.label) else connection.describe(level.developer),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (level.detailed) {
            Spacer(Modifier.height(8.dp))
            StatLine(stringResource(R.string.device_firmware), info?.fw ?: "—")
            StatLine(stringResource(R.string.device_protocol), info?.proto?.let { "v$it" } ?: "—")
        }
        if (level.developer) {
            StatLine(stringResource(R.string.device_schema), "${info?.schema ?: "—"}, ${info?.cols ?: "?"} cols")
            StatLine(stringResource(R.string.device_station), info?.station ?: "—")
            StatLine(stringResource(R.string.device_id), info?.dev ?: "—")
            StatLine(stringResource(R.string.device_boot), info?.boot?.take(16)?.plus("…") ?: "—")
            StatLine(stringResource(R.string.device_dictionary), info?.dict?.take(12)?.plus("…") ?: "—")
            StatLine(stringResource(R.string.device_max_read), info?.maxRead?.let { "$it B" } ?: stringResource(R.string.device_default))
            StatLine(
                stringResource(R.string.device_link),
                listOfNotNull(
                    transport?.label,
                    endpoint,
                    (connection as? ConnectionState.Ready)?.let { "payload ${it.mtu - 3} B" },
                ).joinToString(", ").ifEmpty { "—" },
            )
            if (configReadAtEpochMs != null) {
                val label = stringResource(R.string.device_config_read)
                TickingText({ now -> "$label ${formatAgo(now - configReadAtEpochMs)}" }, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
