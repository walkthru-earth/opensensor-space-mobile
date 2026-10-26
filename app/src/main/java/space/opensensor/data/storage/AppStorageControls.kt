package space.opensensor.data.storage

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import space.opensensor.archive.ArchiveIndex
import space.opensensor.archive.ArchiveStore
import space.opensensor.data.coroutines.IoDispatcher
import space.opensensor.data.history.HistoryDeriver
import space.opensensor.data.history.LegacyFileCleanup
import space.opensensor.data.history.UtcDayRange
import space.opensensor.data.sync.DeviceCoordinator
import space.opensensor.discover.MapCache
import space.opensensor.net.BlockCache
import space.opensensor.settings.OldFilesPlan
import space.opensensor.settings.OldFilesRange
import space.opensensor.settings.OldFilesResult
import space.opensensor.settings.StorageControls
import space.opensensor.settings.StorageUsage
import space.opensensor.storage.DerivedQuota
import space.opensensor.storage.StorageBudget
import space.opensensor.storage.StorageClass
import space.opensensor.storage.StorageRoots
import space.opensensor.storage.StorageTree
import javax.inject.Inject

/**
 * The Settings Storage card on Android (#61). It clears only the derived day files, the feed blocks and
 * the map tiles, and removes old format sensor files from the phone through [LegacyFileCleanup] when the
 * user asks. Sync skips the removed files until the user clears the coordinator's tombstones with
 * Download again. The cleanup's rebuild runs as a [HistoryDeriver] pass, ordered by the shared
 * [space.opensensor.data.history.HistoryOrchestrator].
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
