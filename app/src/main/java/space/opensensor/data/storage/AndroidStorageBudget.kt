package space.opensensor.data.storage

import android.os.storage.StorageManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import space.opensensor.storage.JvmStorageBudget
import space.opensensor.storage.StorageClass
import space.opensensor.storage.StorageException
import space.opensensor.storage.StorageRoots
import java.io.File
import java.io.IOException

/**
 * Android's [StorageBudget][space.opensensor.storage.StorageBudget]: `getAllocatableBytes` of the
 * volume that holds the class, which counts cache the system may clear for us, and `allocateBytes`
 * to claim it before a large write (#55).
 */
class AndroidStorageBudget(
    private val storage: StorageManager,
    roots: StorageRoots,
    io: CoroutineDispatcher,
) : JvmStorageBudget(roots, io) {
    override suspend fun freeBytes(cls: StorageClass): Long = withContext(io) {
        val dir = roots.directories(cls).firstOrNull() ?: return@withContext 0L
        try {
            storage.getAllocatableBytes(storage.getUuidForPath(existingAncestor(File(dir))))
        } catch (e: IOException) {
            throw StorageException("cannot read free space", e)
        }
    }

    override suspend fun reserve(cls: StorageClass, bytes: Long) = withContext(io) {
        val dir = roots.directories(cls).firstOrNull() ?: throw StorageException("no directory for ${cls.name}")
        try {
            storage.allocateBytes(storage.getUuidForPath(existingAncestor(File(dir))), bytes)
        } catch (e: IOException) {
            throw StorageException("not enough free space", e)
        }
    }
}
