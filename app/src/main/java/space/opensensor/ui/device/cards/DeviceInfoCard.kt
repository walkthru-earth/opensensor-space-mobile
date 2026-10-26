package space.opensensor.ui.device.cards

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import space.opensensor.app.R
import space.opensensor.data.ble.BleConnectionFailure
import space.opensensor.data.ble.ConnectionState
import space.opensensor.data.ble.TransportKind
import space.opensensor.data.protocol.DeviceInfo
import space.opensensor.data.settings.DetailLevel
import space.opensensor.designsystem.BrandCard
import space.opensensor.designsystem.Eyebrow
import space.opensensor.designsystem.StatLine
import space.opensensor.designsystem.TickingText
import space.opensensor.designsystem.listSeparator
import space.opensensor.designsystem.spokenList
import space.opensensor.today.describe
import space.opensensor.today.labelText
import space.opensensor.ui.components.NO_VALUE
import space.opensensor.ui.components.agoFormatter

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
            if (connection.isReady && transport != null) stringResource(R.string.device_connected_over, transport.labelText()) else connection.describe(level.developer, BleConnectionFailure.needsPairingRepair(connection)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (level.detailed) {
            Spacer(Modifier.height(8.dp))
            StatLine(stringResource(R.string.device_firmware), info?.fw ?: NO_VALUE)
            StatLine(stringResource(R.string.device_protocol), info?.proto?.let { "v$it" } ?: NO_VALUE)
        }
        if (level.developer) {
            StatLine(stringResource(R.string.device_schema), stringResource(R.string.device_schema_cols, info?.schema ?: NO_VALUE, info?.cols?.toString() ?: NO_VALUE))
            StatLine(stringResource(R.string.device_station), info?.station ?: NO_VALUE)
            StatLine(stringResource(R.string.device_id), info?.dev ?: NO_VALUE)
            StatLine(stringResource(R.string.device_boot), info?.boot?.take(16)?.plus("…") ?: NO_VALUE)
            StatLine(stringResource(R.string.device_dictionary), info?.dict?.take(12)?.plus("…") ?: NO_VALUE)
            StatLine(stringResource(R.string.device_max_read), info?.maxRead?.let { "$it B" } ?: stringResource(R.string.device_default))
            StatLine(
                stringResource(R.string.device_link),
                listOfNotNull(
                    transport?.labelText(),
                    endpoint,
                    (connection as? ConnectionState.Ready)?.let { stringResource(R.string.device_payload, it.mtu - 3) },
                ).let { spokenList(it, listSeparator()) }.ifEmpty { NO_VALUE },
            )
            if (configReadAtEpochMs != null) {
                val label = stringResource(R.string.device_config_read)
                val ago = agoFormatter()
                TickingText({ now -> "$label ${ago(now - configReadAtEpochMs)}" }, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
