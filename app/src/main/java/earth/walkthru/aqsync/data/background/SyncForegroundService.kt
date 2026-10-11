package earth.walkthru.aqsync.data.background

import android.app.ForegroundServiceStartNotAllowedException
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import earth.walkthru.aqsync.data.DeviceConnectionManager
import earth.walkthru.aqsync.data.collectionProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * `dataSync` foreground service that runs for exactly as long as a user-started sync.
 *
 * Why: on Android 16 (OnePlus 7 Pro, 2026-09) a LAN sync died ~2 min after the screen went off —
 * `ConnectivityService` logged "Destroyed live tcp sockets for uids={<ours>}" and `TcpLinkDriver`
 * saw "Software caused connection abort" 6 ms later. That is the background network restriction:
 * once the app is no longer foreground and the grace period ends, its sockets are killed. A
 * foreground service keeps the uid eligible for network for the duration of the transfer.
 *
 * The service owns no logic. [DeviceConnectionRepository] starts it when a transfer begins (the
 * app is in the foreground at that moment, so the API 31+ start restriction does not apply), the
 * service attaches to [DeviceConnectionRepository.sync], mirrors it into the notification and
 * stops itself when `running` turns false. The Stop action cancels the sync through the
 * repository. It is deliberately **not** kept alive while the link is idle (battery): an idle LAN
 * socket may be killed by the OS in the background and the repository reconnects when that happens.
 *
 * API 35+ caps `dataSync` at 6 h per 24 h; [onTimeout] cancels the sync and stops cleanly.
 */
@AndroidEntryPoint
class SyncForegroundService : Service() {

    @Inject
    lateinit var manager: DeviceConnectionManager

    @Inject
    lateinit var notifications: SyncNotifications

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watchJob: Job? = null
    private var inForeground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                manager.cancelInteractive()
                // The watcher sees foregroundNeeded=false and stops the service; if nothing is
                // running any more (race), stop right away.
                if (!manager.snapshots.value.any { it.foregroundNeeded && it.backgroundOwner == null }) finish()
                return START_NOT_STICKY
            }
            else -> {
                if (!inForeground) {
                    val initial = notifications.progress("opensensor.space", collectionProgress(manager.snapshots.value.filter { it.backgroundOwner == null && it.foregroundNeeded }), stopIntent())
                    try {
                        ServiceCompat.startForeground(this, SyncNotifications.FOREGROUND_ID, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                        inForeground = true
                    } catch (e: Exception) {
                        // ForegroundServiceStartNotAllowedException (API 31+) or a type/permission
                        // problem: the sync itself keeps running in the repository; we just cannot
                        // protect it. Log and go away.
                        Log.w(TAG, "startForeground refused: ${e::class.simpleName}: ${e.message}")
                        stopSelf(startId)
                        return START_NOT_STICKY
                    }
                }
                startWatching()
                return START_NOT_STICKY
            }
        }
    }

    private fun startWatching() {
        if (watchJob?.isActive == true) return
        watchJob = scope.launch {
            data class Snapshot(val needed: Boolean, val progress: earth.walkthru.aqsync.data.SyncProgress, val reconnect: earth.walkthru.aqsync.data.ReconnectState?, val name: String?)
            manager.snapshots.map { all ->
                val active = all.filter { it.backgroundOwner == null && it.foregroundNeeded }
                Snapshot(
                    active.isNotEmpty(),
                    collectionProgress(active),
                    active.singleOrNull()?.reconnect,
                    active.singleOrNull()?.name ?: "${active.size} devices"
                )
            }
                .distinctUntilChanged()
                // Progress arrives per CHUNK (hundreds per second on LAN); NotificationManager sheds
                // posts above ~5/s ("Package enqueue rate … Shedding"), so coalesce and pace them.
                .conflate()
                .collect { snap ->
                    if (!snap.needed) {
                        // A sync that just aborted because the link dropped flips `running` a moment
                        // before the reconnect state appears; give it that moment before stopping.
                        delay(STOP_GRACE_MS)
                        if (!manager.snapshots.value.any { it.foregroundNeeded && it.backgroundOwner == null }) finish()
                        return@collect
                    }
                    if (inForeground) {
                        runCatching {
                            getSystemService(android.app.NotificationManager::class.java)
                                .notify(SyncNotifications.FOREGROUND_ID, notifications.progress(snap.name, snap.progress, stopIntent(), snap.reconnect))
                        }
                        delay(NOTIFY_INTERVAL_MS)
                    }
                }
        }
    }

    private fun finish() {
        watchJob?.cancel()
        watchJob = null
        if (inForeground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            inForeground = false
        }
        stopSelf()
    }

    /** API 35+: the system's `dataSync` time budget ran out. Stop the transfer; partials resume later. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "dataSync foreground service timed out (type $fgsType); stopping the sync")
        manager.cancelInteractive()
        finish()
    }

    override fun onDestroy() {
        watchJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun stopIntent(): PendingIntent {
        val intent = Intent(this, SyncForegroundService::class.java).setAction(ACTION_STOP)
        return PendingIntent.getService(this, REQ_STOP, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        private const val TAG = "SyncFgs"
        const val ACTION_STOP = "earth.walkthru.aqsync.action.STOP_SYNC"
        private const val REQ_STOP = 11
        private const val STOP_GRACE_MS = 1_500L
        private const val NOTIFY_INTERVAL_MS = 500L

        /**
         * Start the service for a sync that is about to run / has just started. Must be called while
         * the app is in the foreground (a user tap); otherwise Android 12+ refuses and we log it —
         * the sync then runs unprotected rather than not at all.
         */
        fun start(context: Context): Boolean {
            val intent = Intent(context, SyncForegroundService::class.java)
            return try {
                context.startForegroundService(intent)
                true
            } catch (e: ForegroundServiceStartNotAllowedException) {
                Log.w(TAG, "cannot start the sync foreground service from the background: ${e.message}")
                false
            } catch (e: IllegalStateException) {
                Log.w(TAG, "cannot start the sync foreground service: ${e.message}")
                false
            } catch (e: SecurityException) {
                Log.w(TAG, "cannot start the sync foreground service: ${e.message}")
                false
            }
        }
    }
}
