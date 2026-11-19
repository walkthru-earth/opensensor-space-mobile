package space.opensensor.storage

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
