package space.opensensor.data.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import space.opensensor.data.protocol.DeviceUuids

/** One advertiser seen during a scan. */
data class ScannedDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
    val bonded: Boolean,
    val lastSeenEpochMs: Long,
)

/**
 * The timebox of one scan session. The radio starts in [ScanMode.LOW_LATENCY], restarts in
 * [ScanMode.BALANCED] [settleMs] after the first result or at [lowLatencyMs], and stops at
 * [totalMs]. That is at most two starts per session.
 */
data class ScanPlan(
    val lowLatencyMs: Long = 10_000,
    /** A nearby group of loggers still arrives at full speed after the first one. */
    val settleMs: Long = 2_000,
    val totalMs: Long = 45_000,
)

enum class ScanMode { LOW_LATENCY, BALANCED }

/** One registered scan callback. [start] may be called again after [stop]. */
internal interface ScanRadio {
    fun start(mode: ScanMode)
    fun stop()
}

/**
 * `BluetoothLeScanner` filtered on the OpenSensor Sync service UUID, exposed as a flow of results. The scan
 * follows a [ScanPlan] and the flow completes when the plan stops it. Cancelling stops it sooner.
 *
 * BLUETOOTH_SCAN is declared `neverForLocation`, so no location permission is involved.
 */
@SuppressLint("MissingPermission") // gated by the permission screen before the flow is collected
object BleScanner {
    private const val TAG = "BleScanner"

    /** [Event.Failed.errorCode] when the adapter is missing or off. */
    const val BLUETOOTH_OFF = -1

    /** Admission failure, matching Android's code 6 (named by ScanCallback only from API 33). */
    const val SCANNING_TOO_FREQUENTLY = 6

    sealed interface Event {
        data class Seen(val device: ScannedDevice) : Event

        /** [message] is technical, for logs. [errorCode] is [BLUETOOTH_OFF] or the Android scan error. */
        data class Failed(val errorCode: Int, val message: String) : Event
    }

    fun scan(context: Context, plan: ScanPlan = ScanPlan()): Flow<Event> = flow {
        val adapter = BluetoothStatus.adapter(context)
        val scanner = adapter?.bluetoothLeScanner
        if (adapter == null || !adapter.isEnabled || scanner == null) {
            emit(Event.Failed(BLUETOOTH_OFF, "Bluetooth is off"))
            return@flow
        }
        val events = planned(plan, ScanStartBudget.shared, SystemClock::elapsedRealtime) { send ->
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) = emit(result)
                override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::emit)
                override fun onScanFailed(errorCode: Int) {
                    send(Event.Failed(errorCode, describe(errorCode)))
                }

                private fun emit(r: ScanResult) {
                    val dev = r.device
                    send(
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
            val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(DeviceUuids.SERVICE)).build())
            object : ScanRadio {
                override fun start(mode: ScanMode) {
                    val settings = ScanSettings.Builder()
                        .setScanMode(if (mode == ScanMode.LOW_LATENCY) ScanSettings.SCAN_MODE_LOW_LATENCY else ScanSettings.SCAN_MODE_BALANCED)
                        .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                        .setReportDelay(0)
                        .build()
                    Log.i(TAG, "startScan $mode")
                    scanner.startScan(filters, settings, callback)
                }

                override fun stop() {
                    Log.i(TAG, "stopScan")
                    runCatching { scanner.stopScan(callback) }
                }
            }
        }
        emitAll(events)
    }

    /**
     * Runs [plan] over the radio that [open] registers. [open] gets the sink for its callback.
     * [nowMs] is a monotonic clock for [budget].
     */
    internal fun planned(plan: ScanPlan, budget: ScanStartBudget, nowMs: () -> Long, open: (send: (Event) -> Unit) -> ScanRadio): Flow<Event> = callbackFlow {
        if (!budget.tryAcquire(nowMs())) {
            val code = SCANNING_TOO_FREQUENTLY
            trySend(Event.Failed(code, describe(code)))
            close()
            return@callbackFlow
        }
        val firstSeen = CompletableDeferred<Unit>()
        val radio = open { ev ->
            if (ev is Event.Seen) firstSeen.complete(Unit)
            trySend(ev)
        }
        val radioLock = Any()
        var closed = false
        try {
            radio.start(ScanMode.LOW_LATENCY)
        } catch (e: Exception) {
            radio.stop()
            throw e
        }
        val stepDown = launch {
            withTimeoutOrNull(plan.lowLatencyMs) {
                firstSeen.await()
                delay(plan.settleMs)
            }
            synchronized(radioLock) {
                if (!closed && budget.tryAcquire(nowMs(), reserveStarts = 1)) {
                    radio.stop()
                    radio.start(ScanMode.BALANCED)
                }
            }
        }
        val timeout = launch {
            delay(plan.totalMs)
            channel.close()
        }
        awaitClose {
            stepDown.cancel()
            timeout.cancel()
            synchronized(radioLock) {
                closed = true
                radio.stop()
            }
        }
    }

    private fun describe(code: Int) = when (code) {
        ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "scan already started"
        ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "could not register scan with the Bluetooth stack"
        ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "BLE scanning unsupported on this phone"
        ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "internal Bluetooth error"
        ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> "out of Bluetooth hardware resources"
        SCANNING_TOO_FREQUENTLY -> "scanning too frequently; wait a moment"
        else -> "scan failed ($code)"
    }
}
