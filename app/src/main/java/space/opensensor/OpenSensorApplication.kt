package space.opensensor

import android.app.Application
import android.content.ComponentCallbacks2
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import space.opensensor.data.background.AutoSyncScheduler
import space.opensensor.data.background.SyncTriggers
import space.opensensor.data.coroutines.suspendRunCatching
import space.opensensor.data.history.HistoryDeriver
import space.opensensor.data.history.HistoryStartup
import space.opensensor.data.local.security.LanTokenMigration
import space.opensensor.discover.MapCache
import space.opensensor.settings.SettingsRepository
import space.opensensor.storage.MemoryPressure
import space.opensensor.storage.RetentionPolicy
import space.opensensor.thing.ThingKinds
import javax.inject.Inject
import android.content.res.Configuration as ResConfiguration

/**
 * WorkManager is configured here (default initializer disabled in the manifest) so [SyncWorker]
 * can be Hilt-injected; the scheduler then keeps the periodic auto-sync registration in step
 * with the `known_device.autoSync` rows for the life of the process, and [SyncTriggers] re-arms
 * the event-driven starts (Wi-Fi arrival, companion presence) that outlive the process.
 */
@HiltAndroidApp
class OpenSensorApplication :
    Application(),
    Configuration.Provider {

    // First, so the kinds' metric providers are in the catalog before any field below reads it.
    // ThingModuleTest holds this order.
    @Inject
    lateinit var thingKinds: ThingKinds

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var autoSyncScheduler: AutoSyncScheduler

    @Inject
    lateinit var syncTriggers: SyncTriggers

    @Inject
    lateinit var lanTokenMigration: LanTokenMigration

    @Inject
    lateinit var historyStartup: HistoryStartup

    @Inject
    lateinit var historyDeriver: HistoryDeriver

    @Inject
    lateinit var settings: SettingsRepository

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        // The shared History start (#100, #125), first so nothing below delays it. The janitor sweeps
        // temps and stale partials and adopts orphan finals (#60), the feed block quota applies, then
        // old indexes backfill and History derives, and again whenever a record waits for its index.
        // Screen navigation only observes Room; syncs derive after newly archived files arrive.
        historyStartup.start(appScope + Dispatchers.IO, deriveThings = { historyDeriver.deriveStale(it) }) { historyDeriver.deriveStale() }
        // Shared Compose screens read the first default locale. Make it the language `res/` chose.
        AppLanguage.alignDefault(this)
        // The basemap tile cache stays in cacheDir under its cap, set before any map runs (#61).
        // The default cap is set at once, then the stored cap replaces it without blocking the main
        // thread. A map that already started keeps the default until the next start.
        MapCache.configure(cacheDir.absolutePath, RetentionPolicy.DEFAULT_MAP_CACHE_BYTES)
        appScope.launch {
            val stored = suspendRunCatching { settings.current().retention.mapCacheBytes }.getOrNull()
            if (stored != null && stored != RetentionPolicy.DEFAULT_MAP_CACHE_BYTES) {
                withContext(Dispatchers.Main) { MapCache.configure(cacheDir.absolutePath, stored) }
            }
        }
        appScope.launch {
            suspendRunCatching { lanTokenMigration.migratePlaintextTokens() }
            autoSyncScheduler.start(appScope)
            syncTriggers.register(appScope)
        }
    }

    override fun onConfigurationChanged(newConfig: ResConfiguration) {
        super.onConfigurationChanged(newConfig)
        AppLanguage.alignDefault(this)
    }

    /** Rebuildable memory caches (the discover feed) shrink when the UI hides and clear before a kill (#61). */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        memoryPressureLevel(level)?.let(MemoryPressure::signal)
    }

    internal companion object {
        /**
         * Hidden or cached levels, and the running levels a foreground app gets while the device runs
         * low (API 33 and older), mapped to the shared signal. Other levels ask for nothing.
         */
        @Suppress("DEPRECATION")
        fun memoryPressureLevel(level: Int): MemoryPressure.Level? = when {
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> MemoryPressure.Level.Critical
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> MemoryPressure.Level.Critical
            level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> MemoryPressure.Level.Moderate
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> MemoryPressure.Level.Moderate
            else -> null
        }
    }
}
