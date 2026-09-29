package earth.walkthru.aqsync.data.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import earth.walkthru.aqsync.data.protocol.AqUuids
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [AqTransport] over Android's [BluetoothGatt].
 *
 * The GATT API allows exactly one outstanding operation per connection and reports results through
 * callbacks on a binder thread. This class serializes operations with a [Mutex] and turns every
 * callback into a suspending call via [suspendCancellableCoroutine], so the protocol layer can be
 * written as straight-line coroutine code.
 *
 * Connection sequence (docs/ble-sync-protocol.md, "Transport summary"):
 * connect (TRANSPORT_LE) → discover services → requestMtu(517) → bond if not bonded (passkey shown
 * on the device screen) → enable notifications on status, live, response → Ready.
 *
 * Permissions: every call here needs BLUETOOTH_CONNECT. The UI gates on it before any transport is
 * created ([earth.walkthru.aqsync.ui.permissions]); lint cannot see that, hence the suppression.
 */
@SuppressLint("MissingPermission")
class BleTransport(
    private val context: Context,
    private val device: BluetoothDevice,
    /**
     * Budget for `connectGatt` to report a link. The UI keeps the default (the user is watching);
     * the auto-sync worker passes a short one and never bonds, so an absent device costs seconds.
     */
    private val connectTimeoutMs: Long = CONNECT_TIMEOUT_MS,
    /** False for unattended connects: a device that would need the passkey dialog is a failure, not a prompt. */
    private val allowBonding: Boolean = true,
) : AqTransport {

    override val address: String = device.address
    override val displayName: String? get() = runCatching { device.name }.getOrNull()
    override val kind: TransportKind = TransportKind.BLE
    override val endpoint: String get() = device.address

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _state.asStateFlow()

    @Volatile
    override var mtu: Int = 23
        private set

    private val _status = MutableSharedFlow<ByteArray>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val _live = MutableSharedFlow<ByteArray>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val statusNotifications: Flow<ByteArray> = _status
    override val liveNotifications: Flow<ByteArray> = _live

    private val _responses = Channel<ByteArray>(Channel.UNLIMITED)
    override val responseFrames: ReceiveChannel<ByteArray> = _responses

    /** Bond state mirrored from [BluetoothDevice.ACTION_BOND_STATE_CHANGED]. */
    private val bondState = MutableStateFlow(device.bondState)

    private val opMutex = Mutex()
    private val connectMutex = Mutex()

    /** Exactly one GATT operation may be in flight; its continuation lives here. */
    private val pendingOp = AtomicReference<kotlinx.coroutines.CancellableContinuation<GattResult>?>(null)
    private val pendingConnect = AtomicReference<kotlinx.coroutines.CancellableContinuation<Int>?>(null)

    @Volatile
    private var gatt: BluetoothGatt? = null

    private var info: BluetoothGattCharacteristic? = null
    private var status: BluetoothGattCharacteristic? = null
    private var live: BluetoothGattCharacteristic? = null
    private var control: BluetoothGattCharacteristic? = null
    private var response: BluetoothGattCharacteristic? = null

    private data class GattResult(val status: Int, val value: ByteArray? = null, val extra: Int = 0)

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val dev: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }
            if (dev?.address != device.address) return
            val newState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
            Log.i(TAG, "bond state ${bondState.value} -> $newState")
            bondState.value = newState
        }
    }
    private var bondReceiverRegistered = false

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.i(TAG, "connection state=$newState status=$status")
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                pendingConnect.getAndSet(null)?.resume(status)
            } else {
                pendingConnect.getAndSet(null)?.resume(status)
                pendingOp.getAndSet(null)?.resume(GattResult(if (status == 0) GATT_DISCONNECTED else status))
                _state.value = if (status == BluetoothGatt.GATT_SUCCESS || status == GATT_CONN_TERMINATE_PEER_USER) {
                    ConnectionState.Disconnected
                } else {
                    ConnectionState.Failed("link lost (GATT status $status)", status)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            pendingOp.getAndSet(null)?.resume(GattResult(status))
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            pendingOp.getAndSet(null)?.resume(GattResult(status, extra = mtu))
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            pendingOp.getAndSet(null)?.resume(GattResult(status, value))
        }

        @Deprecated("Pre-API 33 callback")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33) {
                @Suppress("DEPRECATION")
                pendingOp.getAndSet(null)?.resume(GattResult(status, c.value ?: ByteArray(0)))
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            pendingOp.getAndSet(null)?.resume(GattResult(status))
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            pendingOp.getAndSet(null)?.resume(GattResult(status))
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            dispatchNotification(c.uuid, value)
        }

        @Deprecated("Pre-API 33 callback")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) {
                @Suppress("DEPRECATION")
                dispatchNotification(c.uuid, c.value ?: ByteArray(0))
            }
        }
    }

    private fun dispatchNotification(uuid: UUID, value: ByteArray) {
        when (uuid) {
            AqUuids.RESPONSE -> _responses.trySend(value.copyOf())
            AqUuids.STATUS -> _status.tryEmit(value.copyOf())
            AqUuids.LIVE -> _live.tryEmit(value.copyOf())
        }
    }

    // ---------------------------------------------------------------- connect

    override suspend fun connect(): Unit = connectMutex.withLock {
        if (_state.value.isReady) return
        registerBondReceiver()
        try {
            _state.value = ConnectionState.Connecting
            val g = withTimeout(connectTimeoutMs) {
                suspendCancellableCoroutine<Int> { cont ->
                    pendingConnect.set(cont)
                    cont.invokeOnCancellation { pendingConnect.set(null) }
                    gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                    if (gatt == null) {
                        pendingConnect.set(null)
                        cont.resumeWithException(TransportException("connectGatt returned null"))
                    }
                }
            }
            if (g != BluetoothGatt.GATT_SUCCESS) throw TransportException("connect failed (GATT status $g)", g)
            val gattRef = gatt ?: throw TransportException("no GATT after connect")

            _state.value = ConnectionState.DiscoveringServices
            // A short settle avoids the well-known 133 on some stacks when discovery starts instantly.
            delay(300)
            val disc = op("discoverServices", 20_000) { gattRef.discoverServices() }
            if (disc.status != BluetoothGatt.GATT_SUCCESS) throw TransportException("service discovery failed (GATT status ${disc.status})", disc.status)
            bindCharacteristics(gattRef)

            _state.value = ConnectionState.NegotiatingMtu
            val m = op("requestMtu", 10_000) { gattRef.requestMtu(AqUuids.MTU_REQUEST) }
            mtu = if (m.status == BluetoothGatt.GATT_SUCCESS && m.extra > 0) m.extra else 23
            if (mtu < AqUuids.MTU_MINIMUM) {
                throw TransportException("device only granted MTU $mtu; the protocol requires ≥ ${AqUuids.MTU_MINIMUM}")
            }

            // "createBond() before first characteristic access if not bonded". Every characteristic
            // requires an encrypted and authenticated link (passkey typed from the device screen).
            ensureBonded()

            _state.value = ConnectionState.EnablingNotifications
            enableNotifications(gattRef, status ?: missing("status"))
            enableNotifications(gattRef, live ?: missing("live"))
            enableNotifications(gattRef, response ?: missing("response"))

            _state.value = ConnectionState.Ready(mtu)
            Log.i(TAG, "ready: mtu=$mtu bonded=${bondState.value == BluetoothDevice.BOND_BONDED}")
        } catch (e: CancellationException) {
            teardown()
            _state.value = ConnectionState.Disconnected
            throw e
        } catch (e: Exception) {
            teardown()
            val reason = when (e) {
                is TimeoutCancellationException -> "timed out while connecting"
                is TransportException -> e.message ?: "connection failed"
                else -> e.message ?: e::class.simpleName ?: "connection failed"
            }
            _state.value = ConnectionState.Failed(reason, (e as? TransportException)?.gattStatus)
            throw TransportException(reason, (e as? TransportException)?.gattStatus, e)
        }
    }

    private fun bindCharacteristics(g: BluetoothGatt) {
        val service = g.getService(AqUuids.SERVICE)
            ?: throw TransportException("AQ Sync service ${AqUuids.SERVICE} not found on this device")
        info = service.getCharacteristic(AqUuids.INFO) ?: missing("info")
        status = service.getCharacteristic(AqUuids.STATUS) ?: missing("status")
        live = service.getCharacteristic(AqUuids.LIVE) ?: missing("live")
        control = service.getCharacteristic(AqUuids.CONTROL) ?: missing("control")
        response = service.getCharacteristic(AqUuids.RESPONSE) ?: missing("response")
    }

    private fun missing(name: String): Nothing =
        throw TransportException("characteristic `$name` missing from the AQ Sync service")

    private suspend fun ensureBonded() {
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            bondState.value = BluetoothDevice.BOND_BONDED
            return
        }
        if (!allowBonding) throw TransportException("device is not bonded; pairing needs the app open")
        _state.value = ConnectionState.Bonding
        if (device.bondState != BluetoothDevice.BOND_BONDING) {
            if (!device.createBond()) throw TransportException("could not start pairing")
        }
        awaitBond()
    }

    /** Wait for the user to type the 6-digit passkey; the system dialog is driven by the stack. */
    private suspend fun awaitBond() {
        val result = withTimeout(BOND_TIMEOUT_MS) {
            bondState.first { it == BluetoothDevice.BOND_BONDED || it == BluetoothDevice.BOND_NONE }
        }
        if (result != BluetoothDevice.BOND_BONDED) {
            throw TransportException("pairing was cancelled or the passkey was wrong")
        }
        // Give the stack a moment to raise the link to authenticated before the next ATT request.
        delay(500)
    }

    private suspend fun enableNotifications(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
        if (!g.setCharacteristicNotification(c, true)) {
            throw TransportException("setCharacteristicNotification failed for ${c.uuid}")
        }
        val cccd = c.getDescriptor(AqUuids.CCCD)
            ?: throw TransportException("CCCD missing on ${c.uuid}")
        val r = opWithAuthRetry("writeCCCD ${c.uuid}") {
            writeDescriptorCompat(g, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        }
        if (r.status != BluetoothGatt.GATT_SUCCESS) {
            throw TransportException("enabling notifications on ${c.uuid} failed (GATT status ${r.status})", r.status)
        }
    }

    // ------------------------------------------------------------ operations

    /**
     * Run one GATT operation: [start] issues the call; the matching callback resumes us. Exactly one
     * operation may be outstanding, enforced by [opMutex].
     */
    private suspend fun op(name: String, timeoutMs: Long = OP_TIMEOUT_MS, start: () -> Boolean): GattResult =
        opMutex.withLock {
            try {
                withTimeout(timeoutMs) {
                    suspendCancellableCoroutine { cont ->
                        pendingOp.set(cont)
                        cont.invokeOnCancellation { pendingOp.compareAndSet(cont, null) }
                        val started = try {
                            start()
                        } catch (e: Exception) {
                            false
                        }
                        if (!started) {
                            pendingOp.compareAndSet(cont, null)
                            cont.resumeWithException(TransportException("$name could not be issued (stack busy or link down)"))
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                pendingOp.set(null)
                throw TransportException("$name timed out", cause = e)
            }
        }

    /**
     * Android reports a not-yet-authenticated link as GATT_INSUFFICIENT_AUTHENTICATION (5),
     * GATT_INSUFFICIENT_ENCRYPTION (15) or the catch-all 133. The stack then starts pairing on its
     * own; we wait for the bond to complete and retry the same operation once.
     */
    private suspend fun opWithAuthRetry(name: String, timeoutMs: Long = OP_TIMEOUT_MS, start: () -> Boolean): GattResult {
        var attempt = 0
        while (true) {
            val r = op(name, timeoutMs, start)
            if (r.status in AUTH_STATUSES && attempt == 0) {
                attempt++
                Log.w(TAG, "$name: GATT status ${r.status}; waiting for bond then retrying")
                val before = _state.value
                if (!allowBonding && device.bondState == BluetoothDevice.BOND_NONE) {
                    throw TransportException("device lost its bond; pairing needs the app open")
                }
                _state.value = ConnectionState.Bonding
                if (device.bondState == BluetoothDevice.BOND_NONE) device.createBond()
                awaitBond()
                // During connect we are still enabling notifications; at runtime (a read/write on a
                // Ready link that the stack re-authenticated) go back to Ready, otherwise the UI
                // shows a stale "Enabling notifications…" banner and requireReady() fails.
                _state.value = if (before is ConnectionState.Ready) before else ConnectionState.EnablingNotifications
                continue
            }
            if (r.status == 5 || r.status == 15) {
                throw TransportException("Bluetooth pairing needs repair. If the device was erased or reflashed, open Android Bluetooth settings, forget its stale pairing, then pair again from this app. Archived files are retained.", r.status)
            }
            return r
        }
    }

    private fun requireReady(): BluetoothGatt =
        gatt?.takeIf { _state.value.isReady } ?: throw TransportException("not connected")

    private suspend fun readCharacteristic(c: BluetoothGattCharacteristic?, name: String): ByteArray {
        val g = requireReady()
        val ch = c ?: missing(name)
        val r = opWithAuthRetry("read $name") { g.readCharacteristic(ch) }
        if (r.status != BluetoothGatt.GATT_SUCCESS) throw TransportException("read $name failed (GATT status ${r.status})", r.status)
        return r.value ?: ByteArray(0)
    }

    override suspend fun readInfo(): ByteArray = readCharacteristic(info, "info")
    override suspend fun readStatus(): ByteArray = readCharacteristic(status, "status")
    override suspend fun readLive(): ByteArray = readCharacteristic(live, "live")

    override suspend fun writeControl(request: ByteArray) {
        require(request.size <= 512) { "control request is ${request.size} bytes; max 512" }
        val g = requireReady()
        val ch = control ?: missing("control")
        val r = opWithAuthRetry("write control") {
            writeCharacteristicCompat(g, ch, request, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        }
        if (r.status != BluetoothGatt.GATT_SUCCESS) throw TransportException("control write failed (GATT status ${r.status})", r.status)
    }

    private fun writeCharacteristicCompat(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, type: Int): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, value, type) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                c.writeType = type
                c.value = value
                g.writeCharacteristic(c)
            }
        }

    private fun writeDescriptorCompat(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                d.value = value
                g.writeDescriptor(d)
            }
        }

    // ------------------------------------------------------------- lifecycle

    override suspend fun disconnect() {
        teardown()
        _state.value = ConnectionState.Disconnected
    }

    private fun teardown() {
        pendingOp.getAndSet(null)?.resume(GattResult(GATT_DISCONNECTED))
        pendingConnect.getAndSet(null)?.resume(GATT_DISCONNECTED)
        gatt?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        gatt = null
        mtu = 23
    }

    override fun close() {
        teardown()
        _responses.close()
        unregisterBondReceiver()
        _state.value = ConnectionState.Disconnected
    }

    private fun registerBondReceiver() {
        if (bondReceiverRegistered) return
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(bondReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(bondReceiver, filter)
        }
        bondReceiverRegistered = true
    }

    private fun unregisterBondReceiver() {
        if (!bondReceiverRegistered) return
        runCatching { context.unregisterReceiver(bondReceiver) }
        bondReceiverRegistered = false
    }

    companion object {
        private const val TAG = "BleTransport"
        const val GATT_DISCONNECTED = -1

        /** HCI 0x13 as surfaced by `onConnectionStateChange`: the device closed the link on purpose. */
        const val GATT_CONN_TERMINATE_PEER_USER = 19
        const val GATT_ERROR_133 = 133
        val AUTH_STATUSES = setOf(
            BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION, // 5
            BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION, // 15
            GATT_ERROR_133,
        )
        const val CONNECT_TIMEOUT_MS = 30_000L
        const val OP_TIMEOUT_MS = 10_000L
        const val BOND_TIMEOUT_MS = 120_000L
    }
}
