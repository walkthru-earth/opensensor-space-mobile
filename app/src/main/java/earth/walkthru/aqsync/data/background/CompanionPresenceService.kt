package earth.walkthru.aqsync.data.background

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import dagger.hilt.android.AndroidEntryPoint
import earth.walkthru.aqsync.data.local.database.KnownDeviceDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Bound by the system while an associated logger is in BLE range (see [CompanionPresence]). The
 * only thing it does on "appeared" is enqueue the same expedited [SyncWorker] run as "Sync now in
 * background" for that device, provided its Auto-sync switch is on — every existing guard (interactive
 * session wins, bonded-only Bluetooth, one link at a time, `dataSync` notification) applies unchanged.
 * "Disappeared" does nothing: a device that walked away mid-transfer is handled by the worker's own
 * connect and read timeouts, and cancelling here would race a transfer over LAN that does not care
 * about BLE range at all.
 *
 * This is a presence trigger, not a "new file" trigger: a phone that sits beside the logger all day
 * gets one appearance event. The periodic request stays as the floor; the advertising-flag scan in
 * the firmware repo's `docs/background-sync-triggers.md` is the planned answer for "something new".
 */
@AndroidEntryPoint
class CompanionPresenceService : CompanionDeviceService() {
    @Inject lateinit var scheduler: AutoSyncScheduler

    @Inject lateinit var presence: CompanionPresence

    @Inject lateinit var knownDeviceDao: KnownDeviceDao

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // API 36+: one callback for every kind of event.
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        when (event.event) {
            DevicePresenceEvent.EVENT_BLE_APPEARED, DevicePresenceEvent.EVENT_BT_CONNECTED -> {
                val address = presence.addressFor(event.associationId)
                Log.i(TAG, "presence event ${event.event} association=${event.associationId} address=${address ?: "?"}")
                if (address != null) appeared(address) else Log.w(TAG, "no address for association ${event.associationId}")
            }
            DevicePresenceEvent.EVENT_BLE_DISAPPEARED, DevicePresenceEvent.EVENT_BT_DISCONNECTED ->
                Log.i(TAG, "presence event ${event.event} association=${event.associationId}: no action")
            else -> Log.i(TAG, "presence event ${event.event} ignored")
        }
    }

    // API 33–35.
    @Deprecated("Deprecated in Java")
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        // The AssociationInfo overload exists from API 33; on 36+ events arrive through onDevicePresenceEvent.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) return
        val address = associationInfo.deviceMacAddress?.toString()?.uppercase()
        Log.i(TAG, "appeared association=${associationInfo.id} address=${address ?: "?"}")
        if (address != null) appeared(address)
    }

    // API 31–32.
    @Deprecated("Deprecated in Java")
    override fun onDeviceAppeared(address: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
        Log.i(TAG, "appeared address=$address")
        appeared(address.uppercase())
    }

    @Deprecated("Deprecated in Java")
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Log.i(TAG, "disappeared association=${associationInfo.id}: no action")
    }

    @Deprecated("Deprecated in Java")
    override fun onDeviceDisappeared(address: String) {
        Log.i(TAG, "disappeared address=$address: no action")
    }

    private fun appeared(address: String) {
        scope.launch {
            val known = knownDeviceDao.get(address)
            when {
                known == null -> Log.i(TAG, "$address appeared but is not a known device; ignoring")
                !known.autoSync -> Log.i(TAG, "${known.name ?: address} appeared; Auto-sync is off, nothing to do")
                else -> {
                    Log.i(TAG, "${known.name ?: address} appeared; enqueueing a background sync")
                    if (scheduler.syncNow(address, SyncTrigger.PRESENCE) == AutoSyncScheduler.Enqueue.ALREADY_QUEUED) Log.i(TAG, "${known.name ?: address}: a triggered run is already queued")
                }
            }
        }
    }

    companion object {
        private const val TAG = "CompanionPresence"
    }
}
