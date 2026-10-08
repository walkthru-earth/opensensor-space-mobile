package earth.walkthru.aqsync.ui.device.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import earth.walkthru.aqsync.R
import earth.walkthru.aqsync.data.protocol.DeviceStatus
import earth.walkthru.aqsync.data.settings.DetailLevel
import earth.walkthru.aqsync.ui.components.BrandCard
import earth.walkthru.aqsync.ui.components.Eyebrow
import earth.walkthru.aqsync.ui.components.NO_VALUE
import earth.walkthru.aqsync.ui.components.StatLine
import earth.walkthru.aqsync.ui.components.TickingText
import earth.walkthru.aqsync.ui.components.WarningNote
import earth.walkthru.aqsync.ui.components.agoFormatter
import earth.walkthru.aqsync.ui.components.formatBytes
import earth.walkthru.aqsync.ui.components.formatUptime

/**
 * Detailed level: the logger's storage counters and microSD use. Problem counters (dropped, errors,
 * missed) appear in Detailed only when they are not zero. Developer adds codec, retained partial
 * files, uptime and free heap.
 */
@Composable
fun StorageCard(status: DeviceStatus?, receivedAtEpochMs: Long?, level: DetailLevel, modifier: Modifier = Modifier) {
    BrandCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Eyebrow(stringResource(R.string.storage_title), modifier = Modifier.weight(1f))
            if (receivedAtEpochMs != null) {
                val template = stringResource(R.string.storage_status_age)
                val ago = agoFormatter()
                TickingText({ now -> template.format(ago(now - receivedAtEpochMs)) })
            }
        }
        Spacer(Modifier.height(8.dp))
        if (status == null) {
            Text(stringResource(R.string.storage_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@BrandCard
        }
        val warn = MaterialTheme.colorScheme.error
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            // `status.fin` counts files finalized since the device booted, not the card total
            // (that is `LIST_END.count` on the Files screen), so say so.
            Counter(stringResource(R.string.storage_files_this_boot), status.fin)
            Counter(stringResource(R.string.storage_buffered), status.buf?.toLong())
            // Firmware v6 keeps an open multi-row-group file on the card between batches. Those
            // rows are safe from a reset but not listable yet. Older firmware never sends the key.
            status.open?.let { Counter(stringResource(R.string.storage_open), it.toLong()) }
            if (level.developer || (status.drop ?: 0) > 0) Counter(stringResource(R.string.storage_dropped), status.drop, if ((status.drop ?: 0) > 0) warn else null)
            if (level.developer || (status.err ?: 0) > 0) Counter(stringResource(R.string.storage_errors), status.err, if ((status.err ?: 0) > 0) warn else null)
            if (level.developer || (status.miss ?: 0) > 0) Counter(stringResource(R.string.storage_missed), status.miss, if ((status.miss ?: 0) > 0) warn else null)
        }
        Spacer(Modifier.height(10.dp))
        StatLine(
            stringResource(R.string.storage_microsd),
            when {
                status.sd == null -> NO_VALUE
                !status.sdMounted -> stringResource(R.string.storage_not_mounted)
                else -> stringResource(
                    R.string.storage_used_of,
                    status.sdUsedKib?.let { formatBytes(it * 1024) } ?: "?",
                    status.sdKib?.let { formatBytes(it * 1024) } ?: "?",
                )
            },
            valueColor = if (status.sd == 0) warn else MaterialTheme.colorScheme.onSurface,
        )
        StatLine(
            stringResource(R.string.storage_rotation),
            status.intervalSeconds?.let { s -> stringResource(R.string.storage_minutes, s / 60) } ?: NO_VALUE,
        )
        if (level.developer) {
            status.openRowGroups?.let { StatLine(stringResource(R.string.storage_open_row_groups), pluralStringResource(R.plurals.row_groups, it, it)) }
            StatLine(stringResource(R.string.storage_codec), status.codec ?: NO_VALUE)
            StatLine(stringResource(R.string.storage_partial), status.part?.toString() ?: NO_VALUE)
            StatLine(stringResource(R.string.storage_uptime), status.upSeconds?.let(::formatUptime) ?: NO_VALUE)
            StatLine(stringResource(R.string.storage_heap), status.heap?.let { formatBytes(it) } ?: NO_VALUE)
        }
        if (status.storageFailed) {
            Spacer(Modifier.height(8.dp))
            WarningNote(stringResource(R.string.storage_failed))
        }
    }
}

@Composable
private fun Counter(label: String, value: Long?, color: Color? = null) {
    Column {
        Text(value?.toString() ?: NO_VALUE, style = MaterialTheme.typography.titleLarge, color = color ?: MaterialTheme.colorScheme.onSurface)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
