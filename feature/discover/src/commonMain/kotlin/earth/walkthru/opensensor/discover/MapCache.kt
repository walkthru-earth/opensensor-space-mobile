package earth.walkthru.opensensor.discover

import kotlinx.coroutines.flow.first
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import org.maplibre.compose.map.DefaultMapRuntime
import org.maplibre.compose.map.MapRuntimeOptions
import org.maplibre.compose.offline.OfflineManagerState

/**
 * The basemap tile cache of the map renderer (#61). Rebuildable, so it lives in the OS cache
 * directory (Android `cacheDir`, iOS `Library/Caches`) under a byte cap, and Settings can clear it.
 * Offline packs the person downloads are not part of it.
 */
object MapCache {
    private const val FILE_NAME = "maplibre-cache.db"

    private var file: Path? = null

    /**
     * Sets the cache file in [cacheDirectory] and its cap. Call once at process start, before the
     * first map. A runtime that already exists keeps its settings, so a late call only records the path.
     */
    fun configure(cacheDirectory: String, maxBytes: Long) {
        val path = Path(cacheDirectory.trimEnd('/'), FILE_NAME)
        file = path
        try {
            DefaultMapRuntime.configure(MapRuntimeOptions(cacheFile = path, maximumCacheSizeBytes = maxBytes))
        } catch (e: IllegalStateException) {
            // The map already ran in this process. The cap applies from the next start.
        }
    }

    /** Bytes the cache uses on disk now, the database and its journal files. Zero before [configure]. */
    fun bytes(): Long {
        val db = file ?: return 0
        return listOf(db.toString(), "$db-wal", "$db-shm", "$db-journal").sumOf { name ->
            SystemFileSystem.metadataOrNull(Path(name))?.size?.coerceAtLeast(0) ?: 0
        }
    }

    /** Drops every cached tile. The map downloads what it shows again, when public data is on. */
    suspend fun clear() {
        val offline = DefaultMapRuntime.instance.offlineManager
        val state = offline.state.first { it !is OfflineManagerState.Loading }
        if (state is OfflineManagerState.Failed) throw state.cause
        offline.clearAmbientCache()
    }
}
