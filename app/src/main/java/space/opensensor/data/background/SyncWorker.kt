package space.opensensor.data.background

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import space.opensensor.collection.KeepAlive
import space.opensensor.collection.KeepAliveKind
import space.opensensor.collection.Lease
import space.opensensor.collection.StopSource
import space.opensensor.data.DeviceConnectionManager
import space.opensensor.data.SyncProgress
import space.opensensor.data.collectionProgress
import space.opensensor.data.coroutines.suspendRunCatching
import space.opensensor.data.history.HistoryDeriver
import space.opensensor.model.ThingId
import java.util.UUID

/**
 * The auto-sync worker. Scheduled by [AutoSyncScheduler] every 15 min (WorkManager's minimum) while
 * any known device has Auto-sync on, and once, expedited, for a trigger or "Collect from all loggers".
 *
 * It asks to run as a **foreground worker** (`dataSync`) with the same progress notification the
 * interactive sync shows, so the transfer survives the app being swiped away and the screen going
 * off — the measured background network restriction (see [SyncForegroundService]) applies to a
 * plain job too. On Android 12+ [setForeground] can be refused while the app is in the background
 * (`ForegroundServiceStartNotAllowedException`); then the work still runs as a plain job, which
 * has network access for its own duration, and only the ongoing notification is missing.
 *
 * Android 16 accounts even foreground workers against JobScheduler runtime quota. Every attempt,
 * including discovery and local derivation, has an eight-minute deadline and accepts earlier
 * WorkManager cancellation. The PROCESS lease is supplied by this worker, including the bounded
 * ordinary-job fallback; it never starts [SyncForegroundService].
 *
 * All decisions live in [AutoSyncRunner]; this class is WorkManager plumbing: foreground info,
 * progress mirroring, the end-of-run summary and the retry policy (a broken-off transfer retries
 * with backoff; an absent device waits for the next period).
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val gateway: AppAutoSyncGateway,
    private val manager: DeviceConnectionManager,
    private val notifications: SyncNotifications,
    private val historyDeriver: HistoryDeriver,
    private val advertScan: AdvertFlagScan,
) : CoroutineWorker(appContext, params) {
    private val owner = id.toString()

    // Stable for this WorkRequest across process restarts and retries, unlike a per-process counter.
    private val notificationId = notificationIdFor(id)

    override suspend fun getForegroundInfo(): ForegroundInfo =
        foregroundInfo("opensensor.space", collectionProgress(manager.snapshots.value.filter { it.backgroundOwner == owner }))

    private fun foregroundInfo(name: String?, progress: SyncProgress): ForegroundInfo {
        val stop = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val n = notifications.progress(name, progress, stop)
        return ForegroundInfo(notificationId, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    override suspend fun doWork(): Result {
        val result = boundedWorkerRun(MAX_RUN_MS) { protection -> runProtected(protection) }
        if (result != null) return result
        Log.i(TAG, "auto-sync deadline reached; releasing worker quota")
        return retryOrGiveUp()
    }

    private suspend fun runProtected(protection: KeepAlive): Result {
        var foreground = false
        try {
            setForeground(getForegroundInfo())
            foreground = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException (API 31+, app in background) or a missing
            // type/permission: run as a plain job instead; WorkManager gives it network for its run.
            Log.w(TAG, "foreground refused (${e::class.simpleName}); running as a plain job")
        }
        val notificationManager = applicationContext.getSystemService(NotificationManager::class.java)
        val startedMs = System.currentTimeMillis()
        val report = try {
            coroutineScope {
                val mirror = launch {
                    manager.snapshots.map { all ->
                        val owned = all.filter { it.backgroundOwner == owner }
                        (owned.singleOrNull()?.name ?: "${owned.size} devices") to collectionProgress(owned)
                    }
                        .distinctUntilChanged()
                        .filter { (_, p) -> p.running }
                        // Progress arrives per CHUNK. Post at reading speed and only while the screen
                        // is on. The run's outcome is the summary posted after it.
                        .postPaced(NOTIFY_INTERVAL_MS, screenInteractive(applicationContext)) { (name, progress) ->
                            if (foreground || notifications.canPost) {
                                runCatching { notificationManager.notify(notificationId, notifications.progress(name, progress, WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))) }
                            }
                        }
                }
                try {
                    val trigger = inputData.getString(KEY_TRIGGER) ?: (if (onlyThing() != null || inputData.getBoolean(KEY_ALL_KNOWN, false)) SyncTrigger.MANUAL.name else "PERIODIC")
                    Log.i(TAG, "auto-sync run: trigger=$trigger")
                    AutoSyncRunner(gateway.forRun(owner, protection) { workerStopSource(stopReason) }, log = { Log.i(TAG, it) }).run(
                        onlyAddress = onlyThing()?.address,
                        allKnown = inputData.getBoolean(KEY_ALL_KNOWN, false),
                        lanOnly = inputData.getBoolean(KEY_LAN_ONLY, false),
                    )
                } finally {
                    mirror.cancel()
                }
            }
        } catch (e: CancellationException) {
            Log.i(TAG, "auto-sync cancelled, stopReason=$stopReason")
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "auto-sync run failed: ${e::class.simpleName}")
            return retryOrGiveUp()
        } finally {
            if (!foreground) runCatching { notificationManager.cancel(notificationId) }
        }

        if (inputData.getString(KEY_TRIGGER) == null && onlyThing() == null && !inputData.getBoolean(KEY_ALL_KNOWN, false)) {
            // Periodic run: the cheapest place to re-arm the offloaded flag scan, which a Bluetooth
            // toggle silently drops (stop + start, de-duplicated by the receiver).
            suspendRunCatching { advertScan.ensureRegisteredNow() }.onFailure { Log.w(TAG, "flag scan re-register failed: ${it::class.simpleName}") }
        }
        if (report.newFiles > 0) {
            // Still inside the worker's run: the tiers for the days that just gained files. Older
            // stale days wait for the charging backfill.
            suspendRunCatching { historyDeriver.deriveAfterSync(startedMs) }.onFailure { Log.w(TAG, "derive failed: ${it::class.simpleName}") }
        }
        if (report.shouldNotify) {
            notifications.postSummary(report.summaryDeviceName, report.newFiles, report.newBytes, report.failures)
        }
        return if (report.transientFailure) retryOrGiveUp() else Result.success()
    }

    /** The one board this run is for, or null for every eligible one. */
    private fun onlyThing(): ThingId.BleAddress? = inputData.getString(KEY_THING)?.let(ThingId::parse) as? ThingId.BleAddress

    /** Backed-off retry for transient trouble, bounded so a flaky link cannot keep the radio busy all period. */
    private fun retryOrGiveUp(): Result =
        if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()

    companion object {
        /** Worker notification IDs start here, above the fixed IDs in [SyncNotifications]. */
        internal const val NOTIFICATION_ID_BASE = 10_000

        /** Notification ID derived from the WorkRequest id, in `[NOTIFICATION_ID_BASE, NOTIFICATION_ID_BASE + 2^28)`. */
        internal fun notificationIdFor(workId: UUID): Int =
            NOTIFICATION_ID_BASE + ((workId.mostSignificantBits xor workId.leastSignificantBits).hashCode() and 0x0FFF_FFFF)

        private const val TAG = "SyncWorker"

        /** Below the ordinary WorkManager ten-minute window, leaving time for cooperative cleanup. */
        internal const val MAX_RUN_MS = 8 * 60_000L

        /** Retries (with the request's exponential backoff) within one period before waiting for the next. */
        const val MAX_RETRIES = BackgroundSyncPolicy.MAX_RETRIES

        /** Input data: restrict a run to this board, a [ThingId.key] of its Bluetooth address. Absent for periodic runs. */
        const val KEY_THING = "thing"

        /** Input data: a manual run over every known device ("Collect from all loggers"). */
        const val KEY_ALL_KNOWN = "all_known"

        /** Input data: [SyncTrigger] name, for the log line; absent on periodic runs. */
        const val KEY_TRIGGER = "trigger"

        /** Input data: try the LAN link only (a Wi-Fi join must not start Bluetooth connects). */
        const val KEY_LAN_ONLY = "lan_only"
    }
}

/**
 * PROCESS here is the executing WorkManager job, promoted by setForeground when permitted.
 * Collection cleanup stays a child of this deadline. After completion or cancellation the
 * protection refuses new leases; WIFI remains the facade's Android lock responsibility.
 */
internal suspend fun <T : Any> boundedWorkerRun(timeoutMs: Long, run: suspend (KeepAlive) -> T): T? =
    withTimeoutOrNull(timeoutMs) {
        val job = currentCoroutineContext().job
        val protection = KeepAlive { kind, _ ->
            if (kind == KeepAliveKind.PROCESS && job.isActive) Lease {} else null
        }
        run(protection)
    }

/** WorkManager's notification cancel action is an explicit user Stop; quota and OS stops are not. */
internal fun workerStopSource(reason: Int): StopSource =
    when (reason) {
        WorkInfo.STOP_REASON_CANCELLED_BY_APP, WorkInfo.STOP_REASON_USER -> StopSource.USER
        WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT -> StopSource.FGS_TIMEOUT
        else -> StopSource.SYSTEM
    }
