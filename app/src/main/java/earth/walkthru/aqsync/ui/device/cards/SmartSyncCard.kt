package earth.walkthru.aqsync.ui.device.cards

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import earth.walkthru.aqsync.R
import earth.walkthru.aqsync.data.ArchiveTimeSummary
import earth.walkthru.aqsync.data.SyncProgress
import earth.walkthru.aqsync.data.settings.DetailLevel
import earth.walkthru.aqsync.ui.components.BrandCard
import earth.walkthru.aqsync.ui.components.CardTone
import earth.walkthru.aqsync.ui.components.Eyebrow
import earth.walkthru.aqsync.ui.components.Peacock
import earth.walkthru.aqsync.ui.components.PillButton

/** Plain-language sync state, derived only from what the repository reports. */
enum class SyncMood { RUNNING, CAUGHT_UP, SOME_FAILED, READY, EMPTY }

fun syncMood(sync: SyncProgress, archive: ArchiveTimeSummary): SyncMood = when {
    sync.running -> SyncMood.RUNNING
    sync.finishedAtEpochMs != null && sync.failures.isNotEmpty() -> SyncMood.SOME_FAILED
    sync.finishedAtEpochMs != null -> SyncMood.CAUGHT_UP
    archive.files > 0 -> SyncMood.READY
    else -> SyncMood.EMPTY
}

/**
 * Smart Sync: the sync state in words and the one primary action of Today. After a clean run the
 * peacock says so (a success moment, not data). Detailed adds how earlier readings were placed in
 * time. Finalizing buffered rows stays a separate action on the clock card.
 */
@Composable
fun SmartSyncCard(sync: SyncProgress, archive: ArchiveTimeSummary, enabled: Boolean, level: DetailLevel, onSync: () -> Unit, modifier: Modifier = Modifier) {
    val mood = syncMood(sync, archive)
    BrandCard(modifier = modifier, tone = CardTone.SOFT) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Eyebrow(stringResource(R.string.sync_title))
                Spacer(Modifier.height(6.dp))
                Text(
                    when (mood) {
                        SyncMood.RUNNING -> pluralStringResource(R.plurals.sync_running, sync.filesTotal, sync.filesDone, sync.filesTotal)
                        SyncMood.CAUGHT_UP -> stringResource(R.string.sync_caught_up)
                        SyncMood.SOME_FAILED -> pluralStringResource(R.plurals.sync_some_failed, sync.failures.size, sync.failures.size)
                        SyncMood.READY -> pluralStringResource(R.plurals.sync_ready, archive.files, archive.files)
                        SyncMood.EMPTY -> stringResource(R.string.sync_empty)
                    },
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            if (mood == SyncMood.CAUGHT_UP) {
                Spacer(Modifier.width(8.dp))
                Peacock(size = 72.dp)
            }
        }
        if (mood == SyncMood.RUNNING) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(progress = { sync.fraction }, modifier = Modifier.fillMaxWidth())
        }
        if (archive.unplacedRows > 0) {
            Text(
                if (level.detailed) {
                    pluralStringResource(R.plurals.sync_unplaced_detail, archive.unplacedRows.toInt().coerceAtLeast(0), archive.unplacedRows)
                } else {
                    stringResource(R.string.sync_unplaced_simple)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        } else if (level.detailed && archive.estimatedRows > 0) {
            Text(
                pluralStringResource(R.plurals.sync_estimated_detail, archive.estimatedRows.toInt().coerceAtLeast(0), archive.estimatedRows),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        PillButton(
            text = stringResource(if (sync.running) R.string.sync_button_running else R.string.sync_button),
            onClick = onSync,
            enabled = enabled && !sync.running,
            modifier = Modifier.fillMaxWidth(),
        )
        if (level.detailed) {
            Text(
                stringResource(R.string.sync_explain),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
