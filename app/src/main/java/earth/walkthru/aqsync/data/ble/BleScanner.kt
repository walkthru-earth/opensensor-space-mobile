package earth.walkthru.aqsync.data.ble

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
import earth.walkthru.aqsync.data.protocol.AqUuids
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
 * Android refuses more than five scan starts per app in 30 s, silently on recent releases. The
 * step down is skipped when it would use the last start, so the next user tap still scans.
 */
internal class ScanStartBudget(private val windowMs: Long = 30_000, private val maxStarts: Int = 5) {
    private val starts = ArrayDeque<Long>()

    @Synchronized
    fun record(nowMs: Long) {
        trim(nowMs)
        starts.addLast(nowMs)
    }

    /** True when one more start still leaves one in the window. */
    @Synchronized
    fun allowsRestart(nowMs: Long): Boolean {
        trim(nowMs)
        return starts.size + 1 < maxStarts
    }

    private fun trim(nowMs: Long) {
        while (starts.isNotEmpty() && nowMs - starts.first() >= windowMs) starts.removeFirst()
    }
}

/**
 * `BluetoothLeScanner` filtered on the AQ Sync service UUID, exposed as a flow of results. The scan
 * follows a [ScanPlan] and the flow completes when the plan stops it. Cancelling stops it sooner.
 *
 * BLUETOOTH_SCAN is declared `neverForLocation`, so no location permission is involved.
 */
@SuppressLint("MissingPermission") // gated by the permission screen before the flow is collected
object BleScanner {
    private const val TAG = "BleScanner"

    /** [Event.Failed.errorCode] when the adapter is missing or off. */
    const val BLUETOOTH_OFF = -1

    private val budget = ScanStartBudget()

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
        val events = planned(plan, budget, SystemClock::elapsedRealtime) { send ->
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
            val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(AqUuids.SERVICE)).build())
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
        val firstSeen = CompletableDeferred<Unit>()
        val radio = open { ev ->
            if (ev is Event.Seen) firstSeen.complete(Unit)
            trySend(ev)
        }
        budget.record(nowMs())
        radio.start(ScanMode.LOW_LATENCY)
        launch {
            withTimeoutOrNull(plan.lowLatencyMs) {
                firstSeen.await()
                delay(plan.settleMs)
            }
            if (budget.allowsRestart(nowMs())) {
                radio.stop()
                budget.record(nowMs())
                radio.start(ScanMode.BALANCED)
            }
        }
        launch {
            delay(plan.totalMs)
            channel.close()
        }
        awaitClose { radio.stop() }
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
