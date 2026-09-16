package earth.walkthru.aqsync.data.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import earth.walkthru.aqsync.data.protocol.AqUuids
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** One advertiser seen during a scan. */
data class ScannedDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
    val bonded: Boolean,
    val lastSeenEpochMs: Long,
)

/**
 * `BluetoothLeScanner` filtered on the AQ Sync service UUID, exposed as a flow of results. The scan
 * runs while the flow is collected and stops on cancellation.
 *
 * BLUETOOTH_SCAN is declared `neverForLocation`, so no location permission is involved.
 */
@SuppressLint("MissingPermission") // gated by the permission screen before the flow is collected
object BleScanner {
    private const val TAG = "BleScanner"

    sealed interface Event {
        data class Seen(val device: ScannedDevice) : Event
        data class Failed(val errorCode: Int, val message: String) : Event
    }

    fun scan(context: Context): Flow<Event> = callbackFlow {
        val adapter = BluetoothStatus.adapter(context)
        val scanner = adapter?.bluetoothLeScanner
        if (adapter == null || !adapter.isEnabled || scanner == null) {
            trySend(Event.Failed(-1, "Bluetooth is off"))
            close()
            return@callbackFlow
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = emit(result)
            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::emit)
            override fun onScanFailed(errorCode: Int) {
                trySend(Event.Failed(errorCode, describe(errorCode)))
            }

            private fun emit(r: ScanResult) {
                val dev = r.device
                trySend(
                    Event.Seen(
                        ScannedDevice(
                            address = dev.address,
                            name = r.scanRecord?.deviceName ?: runCatching { dev.name }.getOrNull(),
                            rssi = r.rssi,
                            bonded = dev.bondState == BluetoothDevice.BOND_BONDED,
                            lastSeenEpochMs = System.currentTimeMillis(),
                        )
                    )
                )
            }
        }
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(AqUuids.SERVICE)).build())
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(0)
            .build()
        Log.i(TAG, "startScan")
        scanner.startScan(filters, settings, callback)
        awaitClose {
            Log.i(TAG, "stopScan")
            runCatching { scanner.stopScan(callback) }
        }
    }

    private fun describe(code: Int) = when (code) {
        ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "scan already started"
        ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "could not register scan with the Bluetooth stack"
        ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "BLE scanning unsupported on this phone"
        ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "internal Bluetooth error"
        ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> "out of Bluetooth hardware resources"
        ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY -> "scanning too frequently; wait a moment"
        else -> "scan failed ($code)"
    }
}
