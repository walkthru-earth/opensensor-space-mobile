package earth.walkthru.aqsync.data.sync

import earth.walkthru.aqsync.storage.StorageBudget
import earth.walkthru.aqsync.storage.StorageClass
import earth.walkthru.aqsync.storage.StorageException

/** Kept free beyond the planned bytes, for receipts, the index and the rest of the phone. */
const val ARCHIVE_HEADROOM_BYTES: Long = 32L * 1024 * 1024

/**
 * The run needs [needed] bytes (the missing bytes of the planned files plus
 * [ARCHIVE_HEADROOM_BYTES]) and the volume has [available]. Thrown before any `OPEN`, or
 * after a file failed to store and the space ran out. Partials stay for resume, and no archive
 * file is removed.
 */
class InsufficientSpaceException(val needed: Long, val available: Long) : Exception("not enough space on this phone, $needed bytes needed, $available available")

/**
 * Makes room for [missingBytes] plus the headroom in [StorageClass.Durable], where the files land.
 * Android's [StorageBudget.reserve] clears other apps' caches to get it. Throws
 * [InsufficientSpaceException] when it does not fit, and nothing of the archive is removed.
 */
suspend fun StorageBudget.requireRoom(missingBytes: Long) {
    if (missingBytes <= 0) return
    val needed = missingBytes + ARCHIVE_HEADROOM_BYTES
    val free = freeBytes(StorageClass.Durable)
    if (free < needed) throw InsufficientSpaceException(needed, free)
    try {
        reserve(StorageClass.Durable, needed)
    } catch (e: StorageException) {
        throw InsufficientSpaceException(needed, freeBytes(StorageClass.Durable))
    }
}
