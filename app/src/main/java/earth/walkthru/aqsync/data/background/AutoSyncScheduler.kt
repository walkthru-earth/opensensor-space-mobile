package earth.walkthru.aqsync.data.background

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import earth.walkthru.aqsync.data.history.DeriveBackfill
import earth.walkthru.aqsync.data.local.database.KnownDeviceDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registers and cancels the WorkManager requests that run [SyncWorker].
 *
 * - **Periodic**: one unique request, 15 min (the platform minimum; Android decides the exact
 *   moment inside each period), battery not low — and **no network constraint**: the Bluetooth
 *   path needs no network, and `NetworkType.CONNECTED` kept a phone with Wi-Fi off and no mobile
 *   data from ever running the job. The LAN side is skipped fast inside the run when no Wi-Fi
 *   network is up ([AppAutoSyncGateway.browseLan]). `KEEP` so an app update or restart does not
 *   reset the schedule. It exists exactly while at least one known device has
 *   Auto-sync on — [start] observes the count in Room and (un)registers accordingly, so the switch
 *   in the UI only has to flip the row.
 * - **Sync now**: one unique expedited one-time request (`RUN_AS_NON_EXPEDITED_WORK_REQUEST` when
 *   the quota is spent), `KEEP` so a double tap does not queue a second run.
 * - **Triggered**: the same one-time request under a second unique name, enqueued by
 *   [CompanionPresenceService] (logger came into BLE range) and [SyncTriggers] (phone joined Wi-Fi);
 *   see the firmware repo's `docs/background-sync-triggers.md` for why these exist.
 *
 * Retry backoff (a transfer that broke off, see [SyncWorker]): exponential from 5 min, so a flaky
 * link is retried a couple of times inside the period without hammering the radio.
 *
 * - **Derive backfill**: one unique [DeriveWorker] request while the phone charges, for stale
 *   history days that a post-sync derive left alone ([DeriveBackfill]).
 */
@Singleton
class AutoSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val knownDeviceDao: KnownDeviceDao,
) : DeriveBackfill {
    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    /** `KEEP`, so many syncs before the next charge queue one backfill. */
    override fun schedule() = scheduleDeriveBackfill()

    fun scheduleDeriveBackfill() {
        workManager.enqueueUniqueWork(DERIVE_NAME, ExistingWorkPolicy.KEEP, deriveBackfillRequest())
    }

    /** Keep the periodic registration in step with the `autoSync` rows for the life of [scope]. */
    fun start(scope: CoroutineScope) {
        scope.launch {
            knownDeviceDao.observeAutoSyncCount().map { it > 0 }.distinctUntilChanged().collect { enabled ->
                Log.i(TAG, if (enabled) "auto-sync on: periodic work registered" else "auto-sync off: periodic work cancelled")
                setPeriodicEnabled(enabled)
            }
        }
    }

    fun setPeriodicEnabled(enabled: Boolean) {
        if (enabled) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(PERIOD_MINUTES, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
                .addTag(TAG_AUTO_SYNC)
                .build()
            workManager.enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        } else {
            workManager.cancelUniqueWork(PERIODIC_NAME)
        }
    }

    /**
     * "Sync now in background" for [address] (Auto-sync switch on or not): runs as soon as the
     * system allows, and the user may leave the app.
     */
    fun syncNow(address: String, trigger: SyncTrigger = SyncTrigger.MANUAL) {
        // Expedited work may only carry network/storage constraints (so no battery rule), and we
        // want none of those either: the Bluetooth path needs no network.
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(workDataOf(SyncWorker.KEY_ADDRESS to address, SyncWorker.KEY_TRIGGER to trigger.name))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .addTag(TAG_AUTO_SYNC)
            .build()
        // Triggered runs queue under their own name so a presence event is not swallowed by a
        // manual run the user just started (KEEP would drop it); at most one of each is pending.
        workManager.enqueueUniqueWork((if (trigger == SyncTrigger.MANUAL) MANUAL_NAME else TRIGGER_NAME) + "-$address", ExistingWorkPolicy.KEEP, request)
    }

    /**
     * An event-driven run over every Auto-sync device ([SyncTriggers]): the Wi-Fi trigger passes
     * [lanOnly] so a Wi-Fi join never turns into Bluetooth connect attempts.
     */
    fun syncOnTrigger(trigger: SyncTrigger, lanOnly: Boolean) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(workDataOf(SyncWorker.KEY_TRIGGER to trigger.name, SyncWorker.KEY_LAN_ONLY to lanOnly))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .addTag(TAG_AUTO_SYNC)
            .build()
        workManager.enqueueUniqueWork(TRIGGER_NAME, ExistingWorkPolicy.KEEP, request)
    }

    /**
     * "Collect from all loggers": one expedited run over every known device, LAN then Bluetooth bond,
     * with bounded parallel device transfers — the maintainer's walk through a site. Same worker, same notification, same
     * summary ("Synced 40 new files from 3 loggers"). Interactive use of a device still wins.
     */
    fun collectAll() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(workDataOf(SyncWorker.KEY_ALL_KNOWN to true))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .addTag(TAG_AUTO_SYNC)
            .build()
        workManager.enqueueUniqueWork(MANUAL_NAME, ExistingWorkPolicy.KEEP, request)
    }

    /** True while a manual or periodic run is queued or running (for the Files menu). */
    fun observeActive(): Flow<Boolean> =
        workManager.getWorkInfosByTagFlow(TAG_AUTO_SYNC).map { infos ->
            // A periodic request is ENQUEUED between runs by definition; only a one-time one waiting counts.
            infos.any { it.state == WorkInfo.State.RUNNING || (it.state == WorkInfo.State.ENQUEUED && it.periodicityInfo == null) }
        }

    companion object {
        private const val TAG = "AutoSyncScheduler"
        const val DERIVE_NAME = "history-derive-backfill"

        /** Charging only. A backfill can rebuild months of days and is never urgent. */
        internal fun deriveBackfillRequest(): OneTimeWorkRequest = OneTimeWorkRequestBuilder<DeriveWorker>()
            .setConstraints(Constraints.Builder().setRequiresCharging(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()
        const val PERIODIC_NAME = "auto-sync-periodic"
        const val MANUAL_NAME = "auto-sync-now"
        const val TRIGGER_NAME = "auto-sync-trigger"
        const val TAG_AUTO_SYNC = "auto-sync"

        /** WorkManager refuses shorter periods (PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS). */
        const val PERIOD_MINUTES = BackgroundSyncPolicy.PERIOD_MS / 60_000L
        const val BACKOFF_MINUTES = BackgroundSyncPolicy.BACKOFF_MS / 60_000L
    }
}
