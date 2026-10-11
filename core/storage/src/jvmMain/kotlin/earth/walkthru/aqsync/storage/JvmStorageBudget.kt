package earth.walkthru.aqsync.storage

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [StorageBudget] from `File.usableSpace` of each class's first root, for the host JVM. Android
 * overrides [freeBytes] and [reserve] with `StorageManager` and keeps [usage].
 */
open class JvmStorageBudget(
    protected val roots: StorageRoots,
    protected val io: CoroutineDispatcher = Dispatchers.IO,
) : StorageBudget {
    override suspend fun freeBytes(cls: StorageClass): Long = withContext(io) {
        val dir = roots.directories(cls).firstOrNull() ?: return@withContext 0L
        existingAncestor(File(dir)).usableSpace
    }

    override suspend fun reserve(cls: StorageClass, bytes: Long) {
        if (bytes > freeBytes(cls)) throw StorageException("not enough free space")
    }

    override suspend fun usage(): Map<StorageClass, Long> = withContext(io) {
        val tree = JvmStorageTree()
        StorageClass.entries.associateWith { cls ->
            roots.directories(cls).sumOf { dir -> tree.list(dir).sumOf { it.bytes } }
        }
    }

    /** The nearest directory that exists, so a root not yet created still reports its volume. */
    protected fun existingAncestor(file: File): File {
        var current: File? = file
        while (current != null && !current.exists()) current = current.parentFile
        return current ?: file
    }
}
