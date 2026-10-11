package earth.walkthru.aqsync.data.background

import android.Manifest
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.content.edit
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import earth.walkthru.aqsync.data.TransportFactory
import earth.walkthru.aqsync.data.ble.ScanStartBudget
import earth.walkthru.aqsync.data.coroutines.ApplicationScope
import earth.walkthru.aqsync.data.coroutines.IoDispatcher
import earth.walkthru.aqsync.data.local.database.KnownDeviceDao
import earth.walkthru.aqsync.data.protocol.Advertisement
import earth.walkthru.aqsync.data.protocol.AqUuids
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The "something new" wake: a `PendingIntent` BLE scan whose filters are offloaded to the phone's
 * Bluetooth controller, so the app is started only when a **bonded, Auto-sync** logger advertises
 * `new_files` or `no_utc` in its protocol-2.1 service data ([Advertisement]) — not merely when it
 * is in range, which is what [CompanionPresence] gives. Two filters per device
 * (`deviceAddress` AND `serviceData` mask on one flag bit each), `SCAN_MODE_LOW_POWER`,
 * `FIRST_MATCH | MATCH_LOST` so the controller reports once per flag flip: the device clears
 * `new_files` after a `LIST`, the controller reports MATCH_LOST, and the next file re-arms it.
 *
 * Needs `BluetoothAdapter.isOffloadedFilteringSupported()`; without it there is no hardware wake
 * and this class does nothing (the periodic run and the presence trigger remain). The registration
 * survives process death but not a Bluetooth toggle or reboot, so [ensureRegistered] is called at
 * every process start, from the periodic worker, and after boot — stop + start, which also resets
 * FIRST_MATCH; [AdvertScanReceiver] de-duplicates on [Advertisement.changeKey] so a re-registration
 * next to a device with a flag still set costs a compare, not a run.
 *
 * Reasoning and what is still to measure: firmware repo `docs/background-sync-triggers.md`.
 */
@Singleton
class AdvertFlagScan @Inject constructor(
    @ApplicationContext private val context: Context,
    private val knownDeviceDao: KnownDeviceDao,
    private val transportFactory: TransportFactory,
    private val scheduler: AutoSyncScheduler,
) {
    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val registration = Mutex()

    /** Register (or re-register) the scan for every bonded Auto-sync device; unregister when there is none. */
    fun ensureRegistered(scope: CoroutineScope) {
        scope.launch { ensureRegisteredNow() }
    }

    suspend fun ensureRegisteredNow() {
        registration.withLock { registerNow() }
    }

    private suspend fun registerNow() {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        val scanner: BluetoothLeScanner = adapter.bluetoothLeScanner ?: return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "no BLUETOOTH_SCAN permission; flag scan not registered")
            return
        }
        val devices = knownDeviceDao.autoSyncDevices().filter { transportFactory.isBonded(it.address) }
        val intent = pendingIntent()
        if (devices.isEmpty()) {
            stop(scanner, intent)
            Log.i(TAG, "no bonded Auto-sync device; flag scan not registered")
            return
        }
        if (!adapter.isOffloadedFilteringSupported) {
            stop(scanner, intent)
            Log.w(TAG, "controller has no offloaded filtering; flag scan unavailable on this phone")
            return
        }
        val service = ParcelUuid(AqUuids.SERVICE)
        val filters = devices.flatMap { device ->
            listOf(Advertisement.FLAG_NEW_FILES, Advertisement.FLAG_NO_UTC).map { flag ->
                val (data, mask) = Advertisement.flagFilter(flag)
                ScanFilter.Builder().setDeviceAddress(device.address).setServiceData(service, data, mask).build()
            }
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_FIRST_MATCH or ScanSettings.CALLBACK_TYPE_MATCH_LOST)
            .setMatchMode(ScanSettings.MATCH_MODE_STICKY)
            .setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
            .build()
        // PendingIntent starts are recorded in the same UID scan history. Keep the existing
        // registration when admission is denied, and leave one slot for foreground discovery.
        if (!ScanStartBudget.shared.tryAcquire(SystemClock.elapsedRealtime(), reserveStarts = 1)) {
            Log.i(TAG, "scan start budget exhausted; retaining the current flag scan")
            return
        }
        stop(scanner, intent)
        try {
            val rc = scanner.startScan(filters, settings, intent)
            if (rc == 0) {
                Log.i(TAG, "flag scan registered for ${devices.size} device(s), ${filters.size} filters")
            } else {
                Log.w(TAG, "flag scan not registered: startScan returned $rc")
            }
        } catch (e: Exception) {
            Log.w(TAG, "flag scan not registered: ${e::class.simpleName}: ${e.message}")
        }
    }

    private fun stop(scanner: BluetoothLeScanner, intent: PendingIntent) {
        try {
            scanner.stopScan(intent)
        } catch (e: SecurityException) {
            Log.d(TAG, "stopScan: ${e::class.simpleName}")
        } catch (e: Exception) {
            Log.d(TAG, "stopScan: ${e::class.simpleName}")
        }
    }

    private fun pendingIntent(): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_SCAN,
            Intent(context, AdvertScanReceiver::class.java).setAction(ACTION_SCAN_RESULT),
            // Mutable: the Bluetooth stack fills in the scan results as extras.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

    /** A batch of results arrived for the registered filters. */
    suspend fun onResults(callbackType: Int, results: List<ScanResult>) {
        if (callbackType == ScanSettings.CALLBACK_TYPE_MATCH_LOST) {
            Log.d(TAG, "match lost for ${results.size} device(s)")
            return
        }
        val now = System.currentTimeMillis()
        for (result in results) {
            val address = result.device?.address?.uppercase() ?: continue
            val advert = Advertisement.decode(result.scanRecord?.getServiceData(ParcelUuid(AqUuids.SERVICE)))
            if (advert == null) {
                Log.i(TAG, "$address matched but carries no 2.1 advertisement; ignoring")
                continue
            }
            val known = knownDeviceDao.get(address)
            if (known == null || !known.autoSync) {
                Log.i(TAG, "$address matched but is not an Auto-sync device; ignoring")
                continue
            }
            val lastKey = prefs.getString(keyFor(address), null)
            val lastMs = prefs.getLong(timeFor(address), 0L)
            when {
                !advert.wantsSync -> Log.i(TAG, "${known.name ?: address}: flags=0x${advert.flags.toString(16)} nothing to act on")
                advert.changeKey == lastKey -> Log.i(TAG, "${known.name ?: address}: same advertisement as the last run (${advert.changeKey}); skipping")
                now - lastMs < MIN_GAP_MS -> Log.i(TAG, "${known.name ?: address}: throttled (${(now - lastMs) / 1000} s since the last advertised run)")
                else -> {
                    Log.i(
                        TAG,
                        "${known.name ?: address}: advertisement ${advert.changeKey} new_files=${advert.newFiles} no_utc=${advert.noUtc} lan=${advert.lanUp}; enqueueing a sync",
                    )
                    prefs.edit { putString(keyFor(address), advert.changeKey); putLong(timeFor(address), now) }
                    scheduler.syncNow(address, SyncTrigger.ADVERTISEMENT)
                }
            }
        }
    }

    private fun keyFor(address: String) = "last_key_$address"
    private fun timeFor(address: String) = "last_ms_$address"

    companion object {
        private const val TAG = "AdvertFlagScan"
        private const val PREFS = "advert_scan"
        private const val REQUEST_SCAN = 2002
        const val ACTION_SCAN_RESULT = "earth.walkthru.aqsync.action.ADVERT_SCAN_RESULT"

        /** A flag that stays set (e.g. `no_utc` on a device no phone can reach) must not run the worker more often than this. */
        const val MIN_GAP_MS = 5 * 60_000L
    }
}

/** Target of the scan `PendingIntent`; explicit, not exported. */
@AndroidEntryPoint
class AdvertScanReceiver : BroadcastReceiver() {
    @Inject lateinit var scan: AdvertFlagScan

    @Inject @ApplicationScope
    lateinit var appScope: CoroutineScope

    @Inject @IoDispatcher
    lateinit var io: CoroutineDispatcher

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AdvertFlagScan.ACTION_SCAN_RESULT) return
        val error = intent.getIntExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, 0)
        if (error != 0) {
            Log.w("AdvertFlagScan", "scan error $error; the periodic run will re-register")
            return
        }
        val type = intent.getIntExtra(BluetoothLeScanner.EXTRA_CALLBACK_TYPE, ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
        val results = IntentCompat.getParcelableArrayListExtra(intent, BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT, ScanResult::class.java) ?: return
        val pending = goAsync()
        launchReceiverWork(appScope, "AdvertFlagScan", finish = pending::finish) {
            withContext(io) { scan.onResults(type, results) }
        }
    }
}
