package earth.walkthru.aqsync.data.background

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import androidx.core.content.edit
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import earth.walkthru.aqsync.data.coroutines.ApplicationScope
import earth.walkthru.aqsync.data.coroutines.IoDispatcher
import earth.walkthru.aqsync.data.local.database.KnownDeviceDao
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Why a one-time [SyncWorker] run was enqueued; logged and shown in the worker's log lines only. */
enum class SyncTrigger {
    /** Files › "Sync now in background" or Home › "Collect from all loggers". */
    MANUAL,

    /** [CompanionPresenceService]: the logger came into BLE range. */
    PRESENCE,

    /** [WifiArrivalReceiver]: the phone joined a Wi-Fi network — LAN devices only. */
    WIFI,

    /** [AdvertScanReceiver]: the logger advertised `new_files` or `no_utc` (protocol 2.1). */
    ADVERTISEMENT,
}

/**
 * Event-driven starts for the auto-sync worker, complementing the 15-minute periodic floor in
 * [AutoSyncScheduler]. Android offers no way for a device on the LAN to wake a phone app, so the
 * wake sources are the system's BLE presence watcher ([CompanionPresence]), the offloaded
 * advertising-flag scan ([AdvertFlagScan], the one the *device* controls) and the phone's own
 * "joined a Wi-Fi network" transition, registered here through
 * `ConnectivityManager.registerNetworkCallback(NetworkRequest, PendingIntent)` (API 23): the
 * broadcast reaches [WifiArrivalReceiver] even when the process is dead. The registration outlives
 * the process but not a reboot, so [BootReceiver] and `Application.onCreate` both call [register];
 * ConnectivityService replaces an existing registration for an equal PendingIntent, so the call
 * is idempotent.
 *
 * SSID and BSSID are location-gated, so the app cannot tell "home" from a café; the run is cheap
 * anyway — [AutoSyncRunner] browses mDNS for ≤ 10 s and stops when no station answers — and it is
 * throttled to one start per [WIFI_THROTTLE_MS], and skipped entirely when no Auto-sync device has
 * a LAN token (a Wi-Fi join says nothing about Bluetooth range).
 */
@Singleton
class SyncTriggers @Inject constructor(
    @ApplicationContext private val context: Context,
    private val knownDeviceDao: KnownDeviceDao,
    private val scheduler: AutoSyncScheduler,
    private val presence: CompanionPresence,
    private val advertScan: AdvertFlagScan,
) {
    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Arm the Wi-Fi trigger, the advertising-flag scan and presence observation for every associated
     * device. Safe at every start; the periodic worker calls [AdvertFlagScan.ensureRegisteredNow] too.
     */
    fun register(scope: CoroutineScope) {
        registerWifiArrival()
        advertScan.ensureRegistered(scope)
        scope.launch {
            for (device in knownDeviceDao.allByLastConnected()) {
                val id = device.companionAssociationId ?: continue
                presence.observe(device.address, id)
            }
        }
    }

    private fun registerWifiArrival() {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()
        try {
            // Immutable on purpose: we do not read the Network extra, only the fact of the transition.
            cm.registerNetworkCallback(request, wifiPendingIntent())
            Log.i(TAG, "Wi-Fi arrival trigger registered")
        } catch (e: Exception) {
            Log.w(TAG, "Wi-Fi arrival trigger not registered: ${e::class.simpleName}: ${e.message}")
        }
    }

    private fun wifiPendingIntent(): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_WIFI,
            Intent(context, WifiArrivalReceiver::class.java).setAction(ACTION_WIFI_AVAILABLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** A Wi-Fi network became available. Called from the receiver on a worker thread. */
    suspend fun onWifiAvailable() {
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST_WIFI_MS, 0L)
        if (now - last < WIFI_THROTTLE_MS) {
            Log.i(TAG, "Wi-Fi available; throttled (${(now - last) / 1000} s since the last trigger)")
            return
        }
        val lanDevices = knownDeviceDao.autoSyncLanCount()
        if (lanDevices == 0) {
            Log.i(TAG, "Wi-Fi available; no Auto-sync device has a LAN token, nothing to do")
            return
        }
        prefs.edit { putLong(KEY_LAST_WIFI_MS, now) }
        Log.i(TAG, "Wi-Fi available; $lanDevices LAN device(s) with Auto-sync on, enqueueing a LAN-only run")
        scheduler.syncOnTrigger(SyncTrigger.WIFI, lanOnly = true)
    }

    companion object {
        private const val TAG = "SyncTriggers"
        private const val PREFS = "sync_triggers"
        private const val KEY_LAST_WIFI_MS = "last_wifi_trigger_ms"
        private const val REQUEST_WIFI = 2001
        const val ACTION_WIFI_AVAILABLE = "earth.walkthru.aqsync.action.WIFI_AVAILABLE"

        /** Wi-Fi flaps and the "already on Wi-Fi at app start" callback must not turn into a sync storm. */
        const val WIFI_THROTTLE_MS = 10 * 60_000L
    }
}

/** Target of the Wi-Fi `PendingIntent`; explicit, not exported. */
@AndroidEntryPoint
class WifiArrivalReceiver : BroadcastReceiver() {
    @Inject lateinit var triggers: SyncTriggers

    @Inject @ApplicationScope
    lateinit var appScope: CoroutineScope

    @Inject @IoDispatcher
    lateinit var io: CoroutineDispatcher

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SyncTriggers.ACTION_WIFI_AVAILABLE) return
        val pending = goAsync()
        launchReceiverWork(appScope, "SyncTriggers", finish = pending::finish) {
            withContext(io) { triggers.onWifiAvailable() }
        }
    }
}

/**
 * Exists so the process starts after a reboot (and after an update): `Application.onCreate` then
 * re-registers the Wi-Fi trigger and re-arms presence observation. WorkManager re-registers its
 * own periodic request by itself.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i("SyncTriggers", "process started by ${intent.action}; triggers re-armed by Application.onCreate")
    }
}
