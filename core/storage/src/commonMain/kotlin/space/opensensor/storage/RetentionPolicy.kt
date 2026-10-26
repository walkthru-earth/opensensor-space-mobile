package space.opensensor.storage

/**
 * How many bytes each rebuildable cache may keep (#61). There is no archive retention. Durable data is
 * never evicted, on the device or the phone. The only removal of sensor files is the user's old
 * format cleanup in Settings (`LegacyFileCleanup` in `:core:history`), on the phone only.
 */
data class RetentionPolicy(
    /** `derived/v1` day files, evicted least recently written first by [QuotaEnforcer]. */
    val derivedQuotaBytes: Long = DEFAULT_DERIVED_QUOTA_BYTES,
    /** The MapLibre ambient tile cache of the Discover map. */
    val mapCacheBytes: Long = DEFAULT_MAP_CACHE_BYTES,
    /** Blocks of the public feed files on disk (`BlockCache`, #94). */
    val blockCacheBytes: Long = DEFAULT_BLOCK_CACHE_BYTES,
) {
    init {
        require(derivedQuotaBytes >= 0 && mapCacheBytes >= 0 && blockCacheBytes >= 0) { "quotas cannot be negative" }
    }

    /**
     * The derived quota inside an OS cache allowance of [cacheQuotaBytes] (Android's
     * `StorageManager.getCacheQuotaBytes`), which the map cache and the block cache share. Never above
     * [derivedQuotaBytes].
     */
    fun derivedQuotaWithin(cacheQuotaBytes: Long): Long =
        minOf(derivedQuotaBytes, (cacheQuotaBytes - mapCacheBytes - blockCacheBytes).coerceAtLeast(0L))

    /**
     * The block cache quota inside an OS cache allowance of [cacheQuotaBytes], after the map cache.
     * It is 0, so the cache empties, while the volume has less than [LOW_FREE_BYTES] free for
     * rebuildable data ([freeBytes]). Never above [blockCacheBytes].
     */
    fun blockCacheQuotaWithin(cacheQuotaBytes: Long, freeBytes: Long): Long =
        if (freeBytes < LOW_FREE_BYTES) 0L else minOf(blockCacheBytes, (cacheQuotaBytes - mapCacheBytes).coerceAtLeast(0L))

    companion object {
        const val DEFAULT_DERIVED_QUOTA_BYTES: Long = 64L * 1024 * 1024
        const val DEFAULT_MAP_CACHE_BYTES: Long = 20L * 1024 * 1024

        /** Several days of feed files at the resolutions one zoom reads. */
        const val DEFAULT_BLOCK_CACHE_BYTES: Long = 16L * 1024 * 1024

        /** Below this much free space the block cache gives its files back. */
        const val LOW_FREE_BYTES: Long = 256L * 1024 * 1024
    }
}
