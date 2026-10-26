@file:OptIn(ExperimentalForeignApi::class)

package space.opensensor.storage

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLVolumeAvailableCapacityForImportantUsageKey
import platform.Foundation.NSURLVolumeAvailableCapacityForOpportunisticUsageKey

/**
 * [StorageBudget] for iOS. Durable and Settings ask for capacity "for important usage", which counts
 * space iOS would free by purging caches. Rebuildable and Scratch ask "for opportunistic usage".
 * iOS has no reservation call, so [reserve] only checks.
 */
class IosStorageBudget(
    private val roots: StorageRoots,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : StorageBudget {
    override suspend fun freeBytes(cls: StorageClass): Long = withContext(io) {
        val dir = roots.directories(cls).firstOrNull() ?: return@withContext 0L
        val key = when (cls) {
            StorageClass.Durable, StorageClass.Settings -> NSURLVolumeAvailableCapacityForImportantUsageKey
            StorageClass.Rebuildable, StorageClass.Scratch -> NSURLVolumeAvailableCapacityForOpportunisticUsageKey
        }
        val url = NSURL.fileURLWithPath(existingAncestor(dir), true)
        (url.resourceValuesForKeys(listOf(key), null)?.get(key) as? NSNumber)?.longLongValue ?: 0L
    }

    override suspend fun reserve(cls: StorageClass, bytes: Long) {
        if (bytes > freeBytes(cls)) throw StorageException("not enough free space")
    }

    override suspend fun usage(): Map<StorageClass, Long> = withContext(io) {
        val tree = IosStorageTree()
        StorageClass.entries.associateWith { cls ->
            roots.directories(cls).sumOf { dir -> tree.list(dir).sumOf { it.bytes } }
        }
    }

    private fun existingAncestor(dir: String): String {
        var current = dir
        while (current.length > 1 && !NSFileManager.defaultManager.fileExistsAtPath(current)) current = current.substringBeforeLast('/').ifEmpty { "/" }
        return current
    }
}
