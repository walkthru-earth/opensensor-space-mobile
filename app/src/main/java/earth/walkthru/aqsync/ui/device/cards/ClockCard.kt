package earth.walkthru.aqsync.ui.device.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import earth.walkthru.aqsync.R
import earth.walkthru.aqsync.data.protocol.DeviceStatus
import earth.walkthru.aqsync.data.protocol.LiveSample
import earth.walkthru.aqsync.data.settings.DetailLevel
import earth.walkthru.opensensor.designsystem.BrandCard
import earth.walkthru.opensensor.designsystem.CardHeader
import earth.walkthru.opensensor.designsystem.InfoContent
import earth.walkthru.opensensor.designsystem.OutlinePillButton
import earth.walkthru.opensensor.designsystem.StatusDot
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * The logger's clock in words, with **Set time from phone** at every level. **Finalize buffered
 * rows** is a power action and appears from Detailed. Developer adds the anchor generation and the
 * last sample's UTC instant.
 */
@Composable
fun ClockCard(
    status: DeviceStatus?,
    live: LiveSample?,
    enabled: Boolean,
    level: DetailLevel,
    onSetTime: () -> Unit,
    onFlush: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val synced = status?.utcSynced
    val label = when (status?.clockSource) {
        1 -> stringResource(R.string.clock_host)
        2 -> stringResource(R.string.clock_rtc)
        0 -> stringResource(R.string.clock_none)
        else -> when (synced) {
            true -> stringResource(R.string.clock_set)
            false -> stringResource(R.string.clock_none)
            null -> stringResource(R.string.clock_unknown)
        }
    }
    BrandCard(modifier = modifier) {
        CardHeader(stringResource(R.string.clock_title), info = InfoContent(stringResource(R.string.clock_title), stringResource(R.string.clock_actions_explain)))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusDot(
                when (synced) {
                    true -> MaterialTheme.colorScheme.secondary
                    false -> MaterialTheme.colorScheme.error
                    null -> MaterialTheme.colorScheme.outline
                },
            )
            Text(label, style = MaterialTheme.typography.bodyLarge)
        }
        if (synced == false) {
            Text(
                stringResource(R.string.clock_none_explain),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (level.developer) {
            status?.gen?.let {
                Text(stringResource(R.string.clock_generation, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            live?.utc?.let { ns ->
                val inst = Instant.ofEpochSecond(ns / 1_000_000_000L, ns % 1_000_000_000L)
                Text(
                    stringResource(R.string.clock_last_sample, DateTimeFormatter.ISO_INSTANT.format(inst)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinePillButton(stringResource(R.string.set_time), onClick = onSetTime, enabled = enabled)
            if (level.detailed) OutlinePillButton(stringResource(R.string.flush), onClick = onFlush, enabled = enabled)
        }
    }
}
