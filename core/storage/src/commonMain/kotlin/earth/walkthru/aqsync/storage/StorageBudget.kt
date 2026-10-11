package earth.walkthru.aqsync.storage

/**
 * Free space as the OS will grant it to each class (#60). The sync run asks [freeBytes] of
 * [StorageClass.Durable] before a download (#55).
 */
interface StorageBudget {
    /** Bytes the OS would let this app write for [cls] now. */
    suspend fun freeBytes(cls: StorageClass): Long

    /**
     * Asks the OS to make [bytes] available for [cls], clearing other apps' caches when it can.
     * Throws [StorageException] when it cannot. A no-op where the OS has no such call (iOS).
     */
    suspend fun reserve(cls: StorageClass, bytes: Long)

    /** Bytes this app stores under each class. */
    suspend fun usage(): Map<StorageClass, Long>
}

/** A [StorageBudget] for tests, with settable free space per class. [reserve] fails past it. */
class FakeStorageBudget(
    free: Map<StorageClass, Long> = StorageClass.entries.associateWith { Long.MAX_VALUE },
    used: Map<StorageClass, Long> = emptyMap(),
) : StorageBudget {
    val free: MutableMap<StorageClass, Long> = free.toMutableMap()
    val used: MutableMap<StorageClass, Long> = used.toMutableMap()

    /** Every successful [reserve], in order. */
    val reservations: MutableList<Pair<StorageClass, Long>> = mutableListOf()

    override suspend fun freeBytes(cls: StorageClass): Long = free[cls] ?: 0L

    override suspend fun reserve(cls: StorageClass, bytes: Long) {
        if (bytes > freeBytes(cls)) throw StorageException("not enough free space")
        reservations += cls to bytes
    }

    override suspend fun usage(): Map<StorageClass, Long> = StorageClass.entries.associateWith { used[it] ?: 0L }
}
