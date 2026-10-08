package earth.walkthru.aqsync.data

import earth.walkthru.aqsync.data.sync.FileOutcome
import earth.walkthru.aqsync.data.sync.FileProgress
import earth.walkthru.aqsync.data.sync.SyncEndCause
import earth.walkthru.aqsync.data.sync.SyncResumePolicy

/** Progress of one collection run over every planned file, as the screens and notifications show it. */
data class SyncProgress(
    val running: Boolean = false,
    val current: FileProgress? = null,
    val filesDone: Int = 0,
    val filesTotal: Int = 0,
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
    val bytesPerSecond: Double = 0.0,
    /** `READ` windows lost to dropped notifications and re-read successfully, over the whole run. */
    val windowsRecovered: Int = 0,
    val outcomes: List<FileOutcome> = emptyList(),
    val startedAtEpochMs: Long? = null,
    val finishedAtEpochMs: Long? = null,
    /** Why the last run ended (null while running or before the first run); see [SyncResumePolicy]. */
    val endCause: SyncEndCause? = null,
) {
    val fraction: Float get() = if (bytesTotal == 0L) 0f else (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f)
    val failures: List<FileOutcome> get() = outcomes.filter { it !is FileOutcome.Synced }
}
