package earth.walkthru.aqsync

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import earth.walkthru.aqsync.data.background.AutoSyncScheduler
import earth.walkthru.aqsync.data.background.SyncTriggers
import earth.walkthru.aqsync.data.history.HistoryDeriver
import earth.walkthru.aqsync.data.history.HistoryIndexer
import earth.walkthru.aqsync.data.local.security.LanTokenMigration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * WorkManager is configured here (default initializer disabled in the manifest) so [SyncWorker]
 * can be Hilt-injected; the scheduler then keeps the periodic auto-sync registration in step
 * with the `known_device.autoSync` rows for the life of the process, and [SyncTriggers] re-arms
 * the event-driven starts (Wi-Fi arrival, companion presence) that outlive the process.
 */
@HiltAndroidApp
class AqSyncApplication :
    Application(),
    Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var autoSyncScheduler: AutoSyncScheduler

    @Inject
    lateinit var syncTriggers: SyncTriggers

    @Inject
    lateinit var lanTokenMigration: LanTokenMigration

    @Inject
    lateinit var historyIndexer: HistoryIndexer

    @Inject
    lateinit var historyDeriver: HistoryDeriver

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            runCatching { lanTokenMigration.migratePlaintextTokens() }
            autoSyncScheduler.start(appScope)
            syncTriggers.register(appScope)
        }
        // Repair old indexes and derived days once per process. Screen navigation only observes
        // Room; interactive and worker syncs already derive after newly archived files arrive.
        appScope.launch(Dispatchers.IO) {
            runCatching { historyIndexer.backfillAll() }.onFailure {
                if (it is CancellationException) throw it
                Log.w(TAG, "history index repair failed", it)
            }
            runCatching { historyDeriver.deriveStale() }.onFailure {
                if (it is CancellationException) throw it
                Log.w(TAG, "history derive repair failed", it)
            }
        }
    }

    private companion object {
        const val TAG = "AqSyncApplication"
    }
}
