package earth.walkthru.aqsync.data.storage

import earth.walkthru.aqsync.data.coroutines.IoDispatcher
import earth.walkthru.aqsync.storage.DerivedQuota
import earth.walkthru.aqsync.storage.StorageBudget
import earth.walkthru.aqsync.storage.StorageClass
import earth.walkthru.opensensor.discover.MapCache
import earth.walkthru.opensensor.settings.StorageControls
import earth.walkthru.opensensor.settings.StorageUsage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** The Settings Storage card on Android (#61). It clears only the derived day files and the map tiles. */
class AppStorageControls @Inject constructor(
    private val budget: StorageBudget,
    private val quota: DerivedQuota,
    @IoDispatcher private val io: CoroutineDispatcher,
) : StorageControls {
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
    }

    override suspend fun clearMapCache() = MapCache.clear()
}
