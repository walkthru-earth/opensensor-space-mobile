package earth.walkthru.aqsync.data.background

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
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
 * - **Per device**: one unique expedited one-time request per device (`RUN_AS_NON_EXPEDITED_WORK_REQUEST`
 *   when the quota is spent), `KEEP` so a double tap does not queue a second run.
 * - **Triggered**: the same one-time request under a second per-device name, enqueued by
 *   [CompanionPresenceService] (logger came into BLE range) and [AdvertFlagScan]. The Wi-Fi join in
 *   [SyncTriggers] and Collect all each have one app-wide name. See the firmware repo's
 *   `docs/background-sync-triggers.md` for why these exist.
 * - Every enqueue checks its unique name first and returns [Enqueue.ALREADY_QUEUED] instead of
 *   claiming a run that `KEEP` would drop.
 *
 * Retry backoff (a transfer that broke off, see [SyncWorker]): exponential from 5 min, so a flaky
 * link is retried a couple of times inside the period without hammering the radio.
 *
 * - **Derive backfill**: one unique [DeriveWorker] request while the phone charges, for stale
 *   history days that a post-sync derive left alone ([DeriveBackfill]).
 */
@Singleton
class AutoSyncScheduler internal constructor(
    private val knownDeviceDao: KnownDeviceDao,
    private val queue: SyncWorkQueue,
) : DeriveBackfill {
    @Inject
    constructor(@ApplicationContext context: Context, knownDeviceDao: KnownDeviceDao) : this(knownDeviceDao, WorkManagerQueue(context))

    /** `KEEP`, so many syncs before the next charge queue one backfill. */
    override fun schedule() = scheduleDeriveBackfill()

    fun scheduleDeriveBackfill() {
        queue.enqueue(DERIVE_NAME, deriveBackfillRequest())
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
            queue.enqueuePeriodic(PERIODIC_NAME, request)
        } else {
            queue.cancel(PERIODIC_NAME)
        }
    }

    /**
     * One run for [address] as soon as the system allows, enqueued by a presence or advert trigger. Returns [Enqueue.ALREADY_QUEUED] when a run
     * for this device under the same name is still queued or running, so the caller never claims
     * a second run that `KEEP` would drop.
     */
    suspend fun syncNow(address: String, trigger: SyncTrigger = SyncTrigger.MANUAL): Enqueue {
        val name = deviceWorkName(address, trigger)
        if (queue.pending(name)) return Enqueue.ALREADY_QUEUED
        val request = oneTime(workDataOf(SyncWorker.KEY_ADDRESS to address, SyncWorker.KEY_TRIGGER to trigger.name))
        // Triggered runs queue under their own name so a presence event is not swallowed by a
        // manual run the user just started; at most one of each is pending per device.
        queue.enqueue(name, request)
        return Enqueue.QUEUED
    }

    /**
     * An event-driven run over every Auto-sync device ([SyncTriggers]): the Wi-Fi trigger passes
     * [lanOnly] so a Wi-Fi join never turns into Bluetooth connect attempts.
     */
    suspend fun syncOnTrigger(trigger: SyncTrigger, lanOnly: Boolean): Enqueue {
        if (queue.pending(TRIGGER_ALL_NAME)) return Enqueue.ALREADY_QUEUED
        queue.enqueue(TRIGGER_ALL_NAME, oneTime(workDataOf(SyncWorker.KEY_TRIGGER to trigger.name, SyncWorker.KEY_LAN_ONLY to lanOnly)))
        return Enqueue.QUEUED
    }

    /**
     * "Collect from all loggers": one expedited run over every known device, LAN then Bluetooth bond,
     * with bounded parallel device transfers, the maintainer's walk through a site. Same worker, same notification, same
     * summary ("Synced 40 new files from 3 loggers"). Interactive use of a device still wins.
     */
    suspend fun collectAll(): Enqueue {
        if (queue.pending(COLLECT_ALL_NAME)) return Enqueue.ALREADY_QUEUED
        queue.enqueue(COLLECT_ALL_NAME, oneTime(workDataOf(SyncWorker.KEY_ALL_KNOWN to true)))
        return Enqueue.QUEUED
    }

    /**
     * True while any one-time background run is queued or running. Combine with the interactive
     * state ([earth.walkthru.aqsync.data.anyCollectionActive]) for an app-wide answer.
     */
    fun observeAnyActive(): Flow<Boolean> = queue.observe(TAG_AUTO_SYNC).map(::anyOneTimeActive).distinctUntilChanged()

    // Expedited work may only carry network/storage constraints (so no battery rule), and we
    // want none of those either: the Bluetooth path needs no network.
    private fun oneTime(input: Data): OneTimeWorkRequest = OneTimeWorkRequestBuilder<SyncWorker>()
        .setInputData(input)
        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
        .addTag(TAG_AUTO_SYNC)
        .build()

    /** Whether a request was added or one under the same unique name was already waiting or running. */
    enum class Enqueue { QUEUED, ALREADY_QUEUED }

    companion object {
        private const val TAG = "AutoSyncScheduler"
        const val DERIVE_NAME = "history-derive-backfill"

        /** Charging only. A backfill can rebuild months of days and is never urgent. */
        internal fun deriveBackfillRequest(): OneTimeWorkRequest = OneTimeWorkRequestBuilder<DeriveWorker>()
            .setConstraints(Constraints.Builder().setRequiresCharging(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()
        const val PERIODIC_NAME = "auto-sync-periodic"
        const val COLLECT_ALL_NAME = "auto-sync-collect-all"
        const val TRIGGER_ALL_NAME = "auto-sync-trigger-all"
        const val TAG_AUTO_SYNC = "auto-sync"

        /** One unique name per device and kind: `auto-sync-now-<address>` or `auto-sync-trigger-<address>`. */
        fun deviceWorkName(address: String, trigger: SyncTrigger): String =
            (if (trigger == SyncTrigger.MANUAL) "auto-sync-now-" else "auto-sync-trigger-") + address

        /** A periodic request is ENQUEUED between runs by definition, so only a one-time one waiting counts. */
        internal fun anyOneTimeActive(work: List<QueuedWork>): Boolean = work.any { it.running || (it.enqueued && !it.periodic) }

        /** WorkManager refuses shorter periods (PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS). */
        const val PERIOD_MINUTES = BackgroundSyncPolicy.PERIOD_MS / 60_000L
        const val BACKOFF_MINUTES = BackgroundSyncPolicy.BACKOFF_MS / 60_000L
    }
}
