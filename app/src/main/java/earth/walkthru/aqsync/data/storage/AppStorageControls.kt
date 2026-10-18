package earth.walkthru.aqsync.data.storage

import android.util.Log
import earth.walkthru.aqsync.archive.ArchiveIndex
import earth.walkthru.aqsync.archive.ArchiveStore
import earth.walkthru.aqsync.data.coroutines.IoDispatcher
import earth.walkthru.aqsync.data.history.HistoryDeriver
import earth.walkthru.aqsync.data.history.LegacyFileCleanup
import earth.walkthru.aqsync.data.history.UtcDayRange
import earth.walkthru.aqsync.data.sync.DeviceCoordinator
import earth.walkthru.aqsync.net.BlockCache
import earth.walkthru.aqsync.storage.DerivedQuota
import earth.walkthru.aqsync.storage.StorageBudget
import earth.walkthru.aqsync.storage.StorageClass
import earth.walkthru.aqsync.storage.StorageRoots
import earth.walkthru.aqsync.storage.StorageTree
import earth.walkthru.opensensor.discover.MapCache
import earth.walkthru.opensensor.settings.OldFilesPlan
import earth.walkthru.opensensor.settings.OldFilesRange
import earth.walkthru.opensensor.settings.OldFilesResult
import earth.walkthru.opensensor.settings.StorageControls
import earth.walkthru.opensensor.settings.StorageUsage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * The Settings Storage card on Android (#61). It clears only the derived day files, the feed blocks and
 * the map tiles, and removes old format sensor files from the phone through [LegacyFileCleanup] when the
 * user asks. Sync skips the removed files until the user clears the coordinator's tombstones with
 * Download again. The cleanup's rebuild runs as a [HistoryDeriver] pass, ordered by the shared
 * [earth.walkthru.aqsync.data.history.HistoryOrchestrator].
 */
class AppStorageControls @Inject constructor(
    private val budget: StorageBudget,
    private val quota: DerivedQuota,
    private val blocks: BlockCache,
    store: ArchiveStore,
    index: ArchiveIndex,
    roots: StorageRoots,
    tree: StorageTree,
    coordinator: DeviceCoordinator,
    deriver: HistoryDeriver,
    @IoDispatcher private val io: CoroutineDispatcher,
) : StorageControls {
    private val pruned = coordinator.pruned
    private val cleanup = LegacyFileCleanup(
        store = store,
        index = index,
        roots = roots,
        tree = tree,
        coordinator = coordinator,
        io = io,
        afterRemoval = { days -> deriver.deriveStale(days.keys) },
        log = { Log.i(TAG, it) },
    )

    override suspend fun usage(): StorageUsage {
        val used = budget.usage()
        return StorageUsage(
            sensorFilesBytes = used[StorageClass.Durable] ?: 0L,
            historyCacheBytes = used[StorageClass.Rebuildable] ?: 0L,
            mapCacheBytes = withContext(io) { MapCache.bytes() },
            temporaryBytes = used[StorageClass.Scratch] ?: 0L,
        )
    }

    override suspend fun clearHistoryCache() {
        withContext(io) { quota.clear() }
        blocks.clear()
    }

    override suspend fun clearMapCache() = MapCache.clear()

    override suspend fun archiveDays(): OldFilesRange? = cleanup.archiveDays()?.let { OldFilesRange(it.fromEpochDay, it.toEpochDay) }

    override suspend fun findOldFiles(range: OldFilesRange?): OldFilesPlan {
        val plan = cleanup.plan(range?.let { UtcDayRange(it.fromEpochDay, it.toEpochDay) })
        return OldFilesPlan(plan.files, plan.bytes, plan.stations, range, skipped = plan.skipped, handle = plan)
    }

    override suspend fun removeOldFiles(plan: OldFilesPlan): OldFilesResult {
        val planned = plan.handle as? LegacyFileCleanup.Plan ?: return OldFilesResult(0, 0, plan.files)
        val report = cleanup.remove(planned)
        return OldFilesResult(report.removed, report.bytes, report.kept, report.failed, report.rebuildFailed)
    }

    override suspend fun removedFiles(): Int = pruned.observeAll().first().size

    override suspend fun downloadRemovedAgain() {
        pruned.clear()
    }

    private companion object {
        const val TAG = "AppStorageControls"
    }
}
