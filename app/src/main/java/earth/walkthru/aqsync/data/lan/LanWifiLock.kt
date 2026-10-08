package earth.walkthru.aqsync.data.lan

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** A Wi-Fi performance lease held while LAN bytes flow; null when the phone has no lock to give. */
fun interface WifiLeases {
    fun acquireLease(): AutoCloseable?
}

/**
 * One [WifiManager.WifiLock] held for exactly as long as a file transfer runs over the LAN pipe.
 *
 * Why: measured on 2026-09-17 (OnePlus 7 Pro, Android 16), a LAN sync with the screen locked saw
 * the phone's Wi-Fi drop into power save — throughput fell from ~60 to ~12 files/min, then TCP
 * stalled for > 5 s and the transfer broke. A full Wi-Fi lock asks the radio to stay out of power
 * save while we hold it. It is never held over Bluetooth (the radio is not involved) and never
 * while the link is idle (battery).
 *
 * Mode: `WIFI_MODE_FULL_HIGH_PERF` on every supported API level, deliberately, even though it is
 * deprecated since API 34:
 * - API 31–33: HIGH_PERF is active with the screen off and the app in the background — the case
 *   we need. `WIFI_MODE_FULL_LOW_LATENCY` is *only* active while the screen is on **and** the app
 *   is in the foreground (a `dataSync` foreground service does not count; AOSP `WifiLockManager`
 *   checks `IMPORTANCE_FOREGROUND`), so it would do nothing in that scenario.
 * - API 34+: the framework converts a HIGH_PERF request into a LOW_LATENCY lock (`DeviceConfig`
 *   `high_perf_lock_deprecated`, default true), with LOW_LATENCY's screen-on + foreground rule.
 *   Requesting LOW_LATENCY ourselves would change nothing there, so HIGH_PERF is never worse.
 * Consequence, recorded rather than hidden: on Android 14+ a screen-off LAN sync is **not**
 * protected by this lock; what saves it is the repository resuming the sync after the lost link.
 *
 * Needs the `WAKE_LOCK` manifest permission (a WifiLock is a wake lock for the Wi-Fi radio); the
 * app holds no CPU wake lock.
 */
@Singleton
class LanWifiLock @Inject constructor(@ApplicationContext private val context: Context) : WifiLeases {

    private var lock: WifiManager.WifiLock? = null
    private val leases = LanTransferLeases(::acquireResource, ::releaseResource)

    /** Each transfer closes its own lease in finally; the last LAN transfer releases the radio. */
    override fun acquireLease(): AutoCloseable? = leases.acquire()

    private fun acquireResource(): Boolean {
        val wifi = context.getSystemService(WifiManager::class.java) ?: run {
            Log.w(TAG, "no WifiManager; LAN sync runs without a Wi-Fi lock")
            return false
        }
        return try {
            @Suppress("DEPRECATION") // see the class comment: HIGH_PERF is the right request on every API we support
            val l = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, TAG).also { it.setReferenceCounted(false) }
            l.acquire()
            lock = l
            Log.i(TAG, "Wi-Fi lock acquired for the LAN transfer")
            true
        } catch (e: RuntimeException) {
            // SecurityException without WAKE_LOCK, or an OEM stack that refuses: log, keep syncing.
            Log.w(TAG, "Wi-Fi lock refused: ${e::class.simpleName}: ${e.message}")
            false
        }
    }

    private fun releaseResource() {
        val l = lock ?: return
        lock = null
        if (l.isHeld) {
            runCatching { l.release() }
                .onSuccess { Log.i(TAG, "Wi-Fi lock released") }
                .onFailure { Log.w(TAG, "Wi-Fi lock release failed: ${it.message}") }
        }
    }

    val isHeld: Boolean get() = leases.activeCount > 0

    private companion object {
        const val TAG = "AqSync:LanWifiLock"
    }
}

/** Android-free owner accounting, so a failed acquire or repeated close cannot release another owner. */
internal class LanTransferLeases(
    private val acquireResource: () -> Boolean,
    private val releaseResource: () -> Unit,
) {
    private var owners = 0

    val activeCount: Int @Synchronized get() = owners

    @Synchronized
    fun acquire(): AutoCloseable? {
        if (owners == 0 && !acquireResource()) return null
        owners++
        return object : AutoCloseable {
            private var closed = false

            override fun close() = synchronized(this@LanTransferLeases) {
                if (!closed) {
                    closed = true
                    owners--
                    if (owners == 0) releaseResource()
                }
            }
        }
    }
}
