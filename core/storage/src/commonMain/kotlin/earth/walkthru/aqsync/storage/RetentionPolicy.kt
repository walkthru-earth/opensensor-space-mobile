package earth.walkthru.aqsync.storage

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
) {
    init {
        require(derivedQuotaBytes >= 0 && mapCacheBytes >= 0) { "quotas cannot be negative" }
    }

    /**
     * The derived quota inside an OS cache allowance of [cacheQuotaBytes] (Android's
     * `StorageManager.getCacheQuotaBytes`), which the map cache shares. Never above [derivedQuotaBytes].
     */
    fun derivedQuotaWithin(cacheQuotaBytes: Long): Long = minOf(derivedQuotaBytes, (cacheQuotaBytes - mapCacheBytes).coerceAtLeast(0L))

    companion object {
        const val DEFAULT_DERIVED_QUOTA_BYTES: Long = 64L * 1024 * 1024
        const val DEFAULT_MAP_CACHE_BYTES: Long = 20L * 1024 * 1024
    }
}
