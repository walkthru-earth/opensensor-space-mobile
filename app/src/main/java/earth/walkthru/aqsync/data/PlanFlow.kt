package earth.walkthru.aqsync.data

import earth.walkthru.aqsync.data.local.database.ArchivedFile
import earth.walkthru.aqsync.data.protocol.DeviceInfo
import earth.walkthru.aqsync.data.protocol.Frame
import earth.walkthru.aqsync.data.sync.ArchivedIdentity
import earth.walkthru.aqsync.data.sync.FileOutcome
import earth.walkthru.aqsync.data.sync.FileSyncState
import earth.walkthru.aqsync.data.sync.SyncPlan
import earth.walkthru.aqsync.data.sync.SyncPlanner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Manifest joined with the archive, with the running transfer on top. The base plan, which walks
 * the partial directory, is rebuilt only when the listing, the station, the archive table or the
 * running flag changes. A progress tick only redoes the cheap overlay (#57, performance audit B2).
 */
internal fun planWithProgress(
    manifest: Flow<ManifestState>,
    info: Flow<DeviceInfo?>,
    archived: Flow<List<ArchivedFile>>,
    sync: Flow<SyncProgress>,
    partialLengths: (String) -> Map<String, Long>,
    planner: (String, List<Frame.File>, List<ArchivedIdentity>, Map<String, Long>) -> SyncPlan = SyncPlanner::plan,
): Flow<SyncPlan?> {
    val base = combine(
        manifest.map { it.last }.distinctUntilChanged(),
        info.map { it?.station }.distinctUntilChanged(),
        archived,
        sync.map { it.running }.distinctUntilChanged(),
    ) { loaded, station, rows, _ ->
        // The last successful listing, kept through a refresh or its failure (see ManifestState).
        if (station == null || loaded == null) return@combine null
        val ids = rows.map { ArchivedIdentity(it.station, it.name, it.bytes, it.crc32) }
        planner(station, loaded.files, ids, partialLengths(station))
    }
    return combine(base, sync) { plan, progress -> plan?.let { overlayProgress(it, progress) } }
}

private fun overlayProgress(plan: SyncPlan, sync: SyncProgress): SyncPlan {
    if (!sync.running && sync.outcomes.isEmpty()) return plan
    val failures = sync.failures.associateBy { it.name }
    val entries = plan.entries.map { e ->
        val cur = sync.current
        when {
            sync.running && cur != null && cur.name == e.name && e.state != FileSyncState.SYNCED ->
                e.copy(state = FileSyncState.SYNCING, partialBytes = cur.bytesDone)
            e.state != FileSyncState.SYNCED && failures[e.name] != null -> {
                val f = failures.getValue(e.name)
                val msg = when (f) {
                    is FileOutcome.Mismatch -> f.reason
                    is FileOutcome.DeviceError -> f.message
                    is FileOutcome.Failed -> f.message
                    is FileOutcome.Synced -> null
                }
                e.copy(state = if (f is FileOutcome.Mismatch) FileSyncState.MISMATCH else e.state, detail = msg)
            }
            else -> e
        }
    }
    return plan.copy(entries = entries)
}
