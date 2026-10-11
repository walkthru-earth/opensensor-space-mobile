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
import earth.walkthru.aqsync.collection.StopSource
import earth.walkthru.aqsync.data.DeviceConnectionManager
import earth.walkthru.aqsync.data.collectionProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Mirrors interactive collection progress. PROCESS leases own its lifetime, including preparation,
 * retry waiting and finalization. Stop ends each controller; loss informs just its generation.
 * The connectedDevice type has no daily cap; dataSync is the permission fallback.
 */
@AndroidEntryPoint
class SyncForegroundService : Service() {

    @Inject
    lateinit var manager: DeviceConnectionManager

    @Inject
    lateinit var notifications: SyncNotifications

    @Inject
    lateinit var keepAlive: AndroidKeepAlive

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watchJob: Job? = null
    private var inForeground = false
    private val ownership = SyncServiceOwnership()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (ownership.command(startId, intent?.getLongExtra(EXTRA_GENERATION, -1L), keepAlive.generation, intent?.action == ACTION_STOP, inForeground)) {
            SyncServiceOwnership.Decision.STOP_COLLECTIONS -> {
                manager.cancelInteractive(StopSource.USER)
                startWatching()
                finishIfIdle()
                return START_NOT_STICKY
            }
            SyncServiceOwnership.Decision.STOP_IDLE -> { finish(); return START_NOT_STICKY }
            SyncServiceOwnership.Decision.IGNORE -> return START_NOT_STICKY
            SyncServiceOwnership.Decision.ACCEPT_START -> Unit
        }
        if (!inForeground) {
            val initial = notifications.progress("opensensor.space", collectionProgress(manager.snapshots.value.filter { it.backgroundOwner == null && it.foregroundNeeded }), stopIntent())
            try {
                startForegroundTyped(initial)
                inForeground = true
            } catch (e: Exception) {
                Log.w(TAG, "startForeground refused (${e::class.simpleName})")
                keepAlive.onServiceStopped(ownership.generation, AndroidKeepAlive.ServiceLoss.REFUSED)
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }
        startWatching()
        finishIfIdle()
        return START_NOT_STICKY
    }

    private fun startWatching() {
        if (watchJob?.isActive == true) return
        watchJob = scope.launch {
            data class Snapshot(val needed: Boolean, val progress: earth.walkthru.aqsync.data.SyncProgress, val reconnect: earth.walkthru.aqsync.data.ReconnectState?, val name: String?)
            combine(manager.snapshots, keepAlive.processHeld) { all, leased ->
                val active = all.filter { it.backgroundOwner == null && it.foregroundNeeded }
                Snapshot(
                    leased,
                    collectionProgress(active),
                    active.singleOrNull()?.reconnect,
                    active.singleOrNull()?.name ?: if (active.isEmpty()) "opensensor.space" else "${active.size} devices"
                )
            }
                .distinctUntilChanged()
                // Progress arrives per CHUNK (hundreds per second on LAN). Post at reading speed and
                // only while the screen is on. The stop decision is final and always goes through.
                .postPaced(NOTIFY_INTERVAL_MS, screenInteractive(this@SyncForegroundService), isFinal = { !it.needed }) { snap ->
                    if (!snap.needed) {
                        finishIfIdle()
                        return@postPaced
                    }
                    if (inForeground) {
                        runCatching {
                            getSystemService(android.app.NotificationManager::class.java)
                                .notify(SyncNotifications.FOREGROUND_ID, notifications.progress(snap.name, snap.progress, stopIntent(), snap.reconnect))
                        }
                    }
                }
        }
    }

    /**
     * `connectedDevice` first, it has no daily cap. Android 14+ refuses it with a
     * SecurityException when no Bluetooth or network permission is granted, then `dataSync`.
     */
    private fun startForegroundTyped(notification: android.app.Notification) {
        try {
            ServiceCompat.startForeground(this, SyncNotifications.FOREGROUND_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (e: SecurityException) {
            Log.w(TAG, "connectedDevice refused, using dataSync: ${e::class.simpleName}")
            ServiceCompat.startForeground(this, SyncNotifications.FOREGROUND_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        }
    }

    private fun finishIfIdle() {
        // A retained Stop PendingIntent can recreate a service after its leases ended.
        if (ownership.isUnleasedIdle(inForeground, keepAlive.generation)) {
            finish()
            return
        }
        if (!keepAlive.finishIfIdle(ownership.generation)) return
        finish()
    }

    private fun finish() {
        watchJob?.cancel()
        watchJob = null
        if (inForeground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            inForeground = false
        }
        stopSelf(ownership.lastStartId)
    }

    /** Stop immediately on the OS deadline. Generation-targeted callbacks preserve newer intents. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android may queue the timeout with the startId present when it was scheduled.
        // Later Stop or stale start commands in this generation do not invalidate that deadline.
        if (!ownership.acceptsTimeout(startId)) return
        Log.w(TAG, "foreground service timed out (type $fgsType)")
        keepAlive.onServiceStopped(ownership.generation, AndroidKeepAlive.ServiceLoss.TIMEOUT)
        finish()
    }

    override fun onDestroy() {
        keepAlive.onServiceStopped(ownership.generation, AndroidKeepAlive.ServiceLoss.DESTROYED)
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
        private const val EXTRA_GENERATION = "collection_generation"

        /** Start from the interactive tap before collection can suspend; refusal fails that run. */
        fun start(context: Context, generation: Long): Boolean {
            val intent = Intent(context, SyncForegroundService::class.java).putExtra(EXTRA_GENERATION, generation)
            return try {
                context.startForegroundService(intent)
                true
            } catch (e: ForegroundServiceStartNotAllowedException) {
                Log.w(TAG, "cannot start the sync foreground service from the background: ${e::class.simpleName}")
                false
            } catch (e: IllegalStateException) {
                Log.w(TAG, "cannot start the sync foreground service: ${e::class.simpleName}")
                false
            } catch (e: SecurityException) {
                Log.w(TAG, "cannot start the sync foreground service: ${e::class.simpleName}")
                false
            }
        }
    }
}
