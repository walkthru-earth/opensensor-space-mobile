package earth.walkthru.opensensor.settings

/** Bytes this app keeps on the phone, by what may be deleted (#61). */
data class StorageUsage(
    /** Synced sensor files, their receipts and clock anchors. Never deleted by the app. */
    val sensorFilesBytes: Long,
    /** Daily History files, rebuilt from the sensor files when missing. */
    val historyCacheBytes: Long,
    /** Basemap tiles, downloaded again when the map needs them. */
    val mapCacheBytes: Long,
    /** Partial downloads, temporary files and export runs. */
    val temporaryBytes: Long,
)

/**
 * What the Settings Storage card measures and clears. The host wires its archive roots, derived quota
 * and map cache. Clearing touches rebuildable caches only, never the sensor files.
 */
interface StorageControls {
    suspend fun usage(): StorageUsage

    suspend fun clearHistoryCache()

    suspend fun clearMapCache()
}

/** A byte count in megabytes with one decimal, the unit the Storage card uses. */
internal fun megabytes(bytes: Long): String {
    val tenths = (bytes.coerceAtLeast(0) * 10 + 524_288) / 1_048_576
    return "${tenths / 10}.${tenths % 10}"
}
