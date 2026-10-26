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
import earth.walkthru.aqsync.data.AndroidLink
import earth.walkthru.aqsync.data.protocol.AqUuids
import earth.walkthru.aqsync.data.protocol.DeviceInfo
import earth.walkthru.aqsync.link.AuthKind
import earth.walkthru.aqsync.link.DisconnectCause
import earth.walkthru.aqsync.link.FrameSink
import earth.walkthru.aqsync.link.LinkClosedException
import earth.walkthru.aqsync.link.LinkInfo
import earth.walkthru.aqsync.link.LinkTimeoutException
import earth.walkthru.aqsync.link.OnceSink
import earth.walkthru.aqsync.link.PushChannel
import earth.walkthru.aqsync.link.TimeoutPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * One GATT connection as an [AndroidLink].
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
 * [open] runs that sequence and reads `info`. From then on every `response` notification goes
 * straight to the session's sink, `status` and `live` go to its push path and to the read only
 * flows. Nothing is queued here, the session's budget bounds what a slow consumer may hold. A
 * link that drops after Ready reports one typed cause ([BleDisconnectCauses]). Android 16 reports
 * lost bond keys as `ACTION_KEY_MISSING`, which ends the link as `Auth(BOND_MISSING)`. Older
 * Android gets the same result from HCI 0x06 or 0x3D, or from [BleStaleBondDetector] when a bonded
 * link keeps closing after it tried its keys. Closes before that point only show the repair hint.
 *
 * Permissions: every call here needs BLUETOOTH_CONNECT. The UI gates on it before any transport is
 * created ([earth.walkthru.aqsync.ui.permissions]); lint cannot see that, hence the suppression.
 */
@SuppressLint("MissingPermission")
class BleLinkDriver(
    private val context: Context,
    private val device: BluetoothDevice,
    /**
     * Budget for `connectGatt` to report a link. The UI keeps the default (the user is watching);
     * the auto-sync worker passes a short one and never bonds, so an absent device costs seconds.
     */
    private val connectTimeoutMs: Long = CONNECT_TIMEOUT_MS,
    /** False for unattended connects: a device that would need the passkey dialog is a failure, not a prompt. */
    private val allowBonding: Boolean = true,
) : AndroidLink {

    override val frames = FrameRecorder()

    @Volatile
    private var sink: OnceSink? = null

    @Volatile
    private var opened = false

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

    /** Not the frame path. Replies go to the session's sink. Closed and empty. */
    override val responseFrames: ReceiveChannel<ByteArray> = Channel<ByteArray>().also { it.close() }

    /** Bond state mirrored from [BluetoothDevice.ACTION_BOND_STATE_CHANGED]. */
    private val bondState = BleBondAwaiter(device.bondState.toBondState())

    private val opMutex = Mutex()
    private val connectMutex = Mutex()

    /** Exactly one GATT operation may be in flight; its continuation and expected callback live here. */
    private val pendingOp = PendingGattOperation<GattResult> { Log.d(TAG, it) }
    private val pendingConnect = AtomicReference<kotlinx.coroutines.CancellableContinuation<Int>?>(null)
    private val linkFailure = BleConnectionFailure()

    @Volatile
    private var acceptConnectionCallbacks = false

    /** This attempt reached CONNECTED. A 133 before that is an absent sensor, not a stale bond. */
    @Volatile
    private var linkConnected = false

    /** The open reached the bond check, so the link has tried its keys. Under [connectMutex]. */
    private var encryptionAttempted = false

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
            if (intent.action == ACTION_KEY_MISSING) {
                Log.w(TAG, "bond keys missing for this device")
                linkFailure.recordDisconnected(BleDisconnectCauses.KEY_MISSING)
                fail(DisconnectCause.Auth(AuthKind.BOND_MISSING))
                return
            }
            val newState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
            val previousState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1)
            Log.i(TAG, "bond state ${bondState.current.state} -> $newState")
            staleBond.onBondStateChanged(device.address)
            bondState.update(newState.toBondState(), previousState.takeIf { it != -1 }?.toBondState())
        }
    }
    private var bondReceiverRegistered = false

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (!acceptConnectionCallbacks || (gatt != null && gatt !== g)) return
            Log.i(TAG, "connection state=$newState status=$status")
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                linkConnected = true
                pendingConnect.getAndSet(null)?.resume(status)
            } else {
                val failureStatus = linkFailure.recordDisconnected(status)
                pendingConnect.getAndSet(null)?.resume(failureStatus)
                pendingOp.failAny(GattResult(failureStatus))
                if (opened) sink?.onClosed(BleDisconnectCauses.ofStatus(failureStatus))
                _state.value = if (failureStatus == GATT_DISCONNECTED || failureStatus == GATT_CONN_TERMINATE_PEER_USER) {
                    ConnectionState.Disconnected
                } else {
                    ConnectionState.Failed(BleConnectionFailure.message(failureStatus), failureStatus)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            pendingOp.complete(GattCallbackKind.SERVICES_DISCOVERED, null, GattResult(status))
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            pendingOp.complete(GattCallbackKind.MTU_CHANGED, null, GattResult(status, extra = mtu))
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            pendingOp.complete(GattCallbackKind.CHARACTERISTIC_READ, c.uuid, GattResult(status, value))
        }

        @Deprecated("Pre-API 33 callback")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33) {
                @Suppress("DEPRECATION")
                pendingOp.complete(GattCallbackKind.CHARACTERISTIC_READ, c.uuid, GattResult(status, c.value ?: ByteArray(0)))
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            pendingOp.complete(GattCallbackKind.CHARACTERISTIC_WRITE, c.uuid, GattResult(status))
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            // Every CCCD has the same UUID; match on the characteristic that owns it.
            pendingOp.complete(GattCallbackKind.DESCRIPTOR_WRITE, d.characteristic?.uuid, GattResult(status))
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
        val out = sink ?: return
        when (uuid) {
            AqUuids.RESPONSE -> value.copyOf().let {
                frames.rx(it)
                out.onFrame(it)
            }
            AqUuids.STATUS -> value.copyOf().let {
                _status.tryEmit(it)
                out.onPush(PushChannel.STATUS, it)
            }
            AqUuids.LIVE -> value.copyOf().let {
                _live.tryEmit(it)
                out.onPush(PushChannel.LIVE, it)
            }
        }
    }

    // ------------------------------------------------------------ LinkDriver

    override suspend fun open(sink: FrameSink): LinkInfo {
        check(this.sink == null) { "a BleLinkDriver opens once" }
        val once = OnceSink(sink)
        this.sink = once
        try {
            establish()
        } catch (e: TransportException) {
            val cause = BleDisconnectCauses.ofOpenFailure(e)
            once.onClosed(cause)
            throw LinkClosedException(cause, e)
        } catch (e: CancellationException) {
            once.onClosed(DisconnectCause.Deliberate("open cancelled"))
            throw e
        }
        val info = try {
            DeviceInfo.parse(readInfo().decodeToString())
        } catch (e: TransportException) {
            val cause = BleDisconnectCauses.ofOpenFailure(e)
            fail(cause)
            throw LinkClosedException(cause, e)
        } catch (e: CancellationException) {
            fail(DisconnectCause.Deliberate("open cancelled"))
            throw e
        } catch (e: Exception) {
            fail(DisconnectCause.FramingViolation("bad device info"))
            throw LinkClosedException(DisconnectCause.FramingViolation("bad device info"), null)
        }
        opened = true
        // A drop between Ready and here was not reported by the callback, report it now.
        if (!_state.value.isReady) fail(BleDisconnectCauses.ofStatus(GATT_DISCONNECTED))
        return LinkInfo(kind, payloadMax = mtu - 3, info = info)
    }

    override suspend fun send(body: ByteArray, deadline: Duration) {
        val op = body.firstOrNull()?.toInt()?.and(0xFF) ?: 0
        val done = try {
            withTimeoutOrNull(deadline) { writeRequest(body) }
        } catch (e: TransportException) {
            throw LinkClosedException(e.gattStatus?.let(BleDisconnectCauses::ofStatus) ?: DisconnectCause.LocalAbort(), e)
        }
        done ?: throw LinkTimeoutException(DisconnectCause.Timeout(TimeoutPhase.SEND, op))
        frames.tx(body)
    }

    override fun abort(cause: DisconnectCause) = fail(cause)

    /** Reports [cause] once and releases the GATT client and the receiver. Any thread. */
    private fun fail(cause: DisconnectCause) {
        sink?.onClosed(cause)
        teardown()
        unregisterBondReceiver()
        _state.value = BleConnectionFailure.closedState(cause)
    }

    // ---------------------------------------------------------------- connect

    override suspend fun connect() {
        if (!_state.value.isReady) throw TransportException("open the link session, not the transport")
    }

    private suspend fun establish(): Unit = connectMutex.withLock {
        if (_state.value.isReady) return
        registerBondReceiver()
        try {
            linkFailure.reset()
            linkConnected = false
            encryptionAttempted = false
            _state.value = ConnectionState.Connecting
            acceptConnectionCallbacks = true
            // withTimeoutOrNull, so a radio timeout is a typed failure and not a cancellation.
            val g = withTimeoutOrNull(connectTimeoutMs) {
                suspendCancellableCoroutine<Int> { cont ->
                    pendingConnect.set(cont)
                    cont.invokeOnCancellation { pendingConnect.compareAndSet(cont, null) }
                    gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                    if (gatt == null) {
                        if (pendingConnect.compareAndSet(cont, null)) cont.resumeWithException(TransportException("connectGatt returned null"))
                    }
                }
            } ?: throw ConnectTimeoutException()
            linkFailure.exceptionOrNull()?.let { throw ConnectFailedException(it.message, it.gattStatus) }
            if (g != BluetoothGatt.GATT_SUCCESS) throw ConnectFailedException(BleConnectionFailure.message(g), g)
            val gattRef = gatt ?: throw TransportException("no GATT after connect")
            if (device.bondState != BluetoothDevice.BOND_BONDED) staleBond.onUnbondedLink(device.address)

            _state.value = ConnectionState.DiscoveringServices
            // A short settle avoids the well-known 133 on some stacks when discovery starts instantly.
            delay(300)
            // Missing bond keys can drop the link during this settle. Preserve that callback's
            // status instead of replacing it with discoverServices' generic false-start error.
            linkFailure.throwIfFailed()
            val disc = op("discoverServices", GattCallbackKind.SERVICES_DISCOVERED, null, 20_000) { gattRef.discoverServices() }
            if (disc.status != BluetoothGatt.GATT_SUCCESS) throw TransportException("service discovery failed (GATT status ${disc.status})", disc.status)
            bindCharacteristics(gattRef)

            _state.value = ConnectionState.NegotiatingMtu
            val m = op("requestMtu", GattCallbackKind.MTU_CHANGED, null, 10_000) { gattRef.requestMtu(AqUuids.MTU_REQUEST) }
            mtu = if (m.status == BluetoothGatt.GATT_SUCCESS && m.extra > 0) m.extra else 23
            if (mtu < AqUuids.MTU_MINIMUM) {
                throw TransportException("device only granted MTU $mtu; the protocol requires ≥ ${AqUuids.MTU_MINIMUM}")
            }

            // "createBond() before first characteristic access if not bonded". Every characteristic
            // requires an encrypted and authenticated link (passkey typed from the device screen).
            encryptionAttempted = true
            ensureBonded()

            _state.value = ConnectionState.EnablingNotifications
            enableNotifications(gattRef, status ?: missing("status"))
            enableNotifications(gattRef, live ?: missing("live"))
            enableNotifications(gattRef, response ?: missing("response"))

            _state.value = ConnectionState.Ready(mtu)
            staleBond.onReady(device.address)
            Log.i(TAG, "ready: mtu=$mtu bonded=${bondState.current.state == BleBondAwaiter.State.BONDED}")
        } catch (e: CancellationException) {
            teardown()
            _state.value = ConnectionState.Disconnected
            throw e
        } catch (e: Exception) {
            val linkClosed = linkFailure.exceptionOrNull()
            val failure = linkClosed ?: e
            teardown()
            val bonded = device.bondState == BluetoothDevice.BOND_BONDED
            val verdict = staleBond.onOpenFailed(device.address, bonded, linkClosed?.gattStatus?.takeIf { linkConnected }, encryptionAttempted)
            // A stale bond reports as missing keys, so retries stop as on API 36. A hint keeps the
            // link status, so the cause stays retryable while the state shows the repair message.
            val status = if (verdict == BleStaleBondDetector.Verdict.STALE) BleDisconnectCauses.KEY_MISSING else (failure as? TransportException)?.gattStatus
            val reason = if (BleConnectionFailure.isRepairStatus(status) || verdict == BleStaleBondDetector.Verdict.HINT) {
                BleConnectionFailure.PAIRING_REPAIR_MESSAGE
            } else {
                when (failure) {
                    is TransportException -> failure.message ?: "connection failed"
                    else -> failure.message ?: failure::class.simpleName ?: "connection failed"
                }
            }
            if (verdict != BleStaleBondDetector.Verdict.NONE) Log.w(TAG, "bonded link closed again while opening (GATT status ${linkClosed?.gattStatus}, after encryption $encryptionAttempted); stale bond ${verdict.name.lowercase()}")
            Log.w(TAG, "connection failed (GATT status $status): $reason")
            _state.value = ConnectionState.Failed(reason, status)
            throw TransportException(reason, status, e)
        }
    }

    private fun bindCharacteristics(g: BluetoothGatt) {
        val service = g.getService(AqUuids.SERVICE)
            ?: throw TransportException("OpenSensor Sync service ${AqUuids.SERVICE} not found on this device")
        info = service.getCharacteristic(AqUuids.INFO) ?: missing("info")
        status = service.getCharacteristic(AqUuids.STATUS) ?: missing("status")
        live = service.getCharacteristic(AqUuids.LIVE) ?: missing("live")
        control = service.getCharacteristic(AqUuids.CONTROL) ?: missing("control")
        response = service.getCharacteristic(AqUuids.RESPONSE) ?: missing("response")
    }

    private fun missing(name: String): Nothing =
        throw TransportException("characteristic `$name` missing from the OpenSensor Sync service")

    /** Pairing could not finish. [missing] when the phone holds no bond and may not ask for one. */
    class BondException(message: String, val missing: Boolean = false, cause: Throwable? = null) : TransportException(message, cause = cause)

    /** The GATT connect did not answer in time, the sensor is out of range or off. */
    class ConnectTimeoutException : TransportException("timed out while connecting")

    /** The GATT connect itself failed, before any service was discovered. */
    class ConnectFailedException(message: String?, gattStatus: Int?) : TransportException(message ?: "connection failed", gattStatus)

    private suspend fun ensureBonded() {
        // Refresh after an owner forgets a stale bond in Settings; the constructor snapshot may
        // belong to the old pairing. Capture before createBond so fast failure events are retained.
        val beforeRequest = bondState.update(device.bondState.toBondState())
        if (beforeRequest.state == BleBondAwaiter.State.BONDED) return
        if (!allowBonding) throw BondException("device is not bonded; pairing needs the app open", missing = true)
        _state.value = ConnectionState.Bonding
        if (beforeRequest.state != BleBondAwaiter.State.BONDING) {
            if (!device.createBond()) throw TransportException("could not start pairing")
        }
        awaitBond(beforeRequest)
    }

    /** Wait for the user to type the 6-digit passkey; the system dialog is driven by the stack. */
    private suspend fun awaitBond(beforeRequest: BleBondAwaiter.Snapshot) {
        val bonded = try {
            bondState.awaitResult(beforeRequest, BOND_TIMEOUT_MS)
        } catch (e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw BondException("Pairing timed out. Reconnect and enter this sensor's six-digit PIN when Android asks. Keep this app open while pairing.", cause = e)
        }
        if (!bonded) {
            throw BondException("pairing was cancelled or the passkey was wrong")
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
        val r = opWithAuthRetry("writeCCCD ${c.uuid}", GattCallbackKind.DESCRIPTOR_WRITE, c.uuid) {
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
    private suspend fun op(name: String, kind: GattCallbackKind, characteristic: UUID?, timeoutMs: Long = OP_TIMEOUT_MS, start: () -> Boolean): GattResult =
        opMutex.withLock {
            linkFailure.throwIfFailed()
            try {
                withTimeout(timeoutMs) {
                    suspendCancellableCoroutine { cont ->
                        pendingOp.arm(kind, characteristic, cont)
                        cont.invokeOnCancellation { pendingOp.disarm(cont) }
                        val started = try {
                            linkFailure.throwIfFailed()
                            start()
                        } catch (e: Exception) {
                            false
                        }
                        if (!started && pendingOp.disarm(cont)) {
                            cont.resumeWithException(linkFailure.exceptionOrNull() ?: TransportException("$name could not be issued (stack busy or link down)"))
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                // invokeOnCancellation already disarmed this operation; a late callback is dropped.
                throw TransportException("$name timed out", cause = e)
            }
        }

    /**
     * Android reports a not-yet-authenticated link as GATT_INSUFFICIENT_AUTHENTICATION (5),
     * GATT_INSUFFICIENT_ENCRYPTION (15) or the catch-all 133. The stack then starts pairing on its
     * own; we wait for the bond to complete and retry the same operation once.
     */
    private suspend fun opWithAuthRetry(name: String, kind: GattCallbackKind, characteristic: UUID?, timeoutMs: Long = OP_TIMEOUT_MS, start: () -> Boolean): GattResult {
        var attempt = 0
        while (true) {
            val r = op(name, kind, characteristic, timeoutMs, start)
            // A terminal auth disconnect cannot be repaired by retrying an ATT operation on it.
            linkFailure.throwIfFailed()
            if (r.status in AUTH_STATUSES && attempt == 0) {
                attempt++
                Log.w(TAG, "$name: GATT status ${r.status}; waiting for bond then retrying")
                val before = _state.value
                val beforeRequest = bondState.update(device.bondState.toBondState())
                if (!allowBonding && beforeRequest.state == BleBondAwaiter.State.NONE) {
                    throw BondException("device lost its bond; pairing needs the app open", missing = true)
                }
                _state.value = ConnectionState.Bonding
                if (beforeRequest.state == BleBondAwaiter.State.NONE && !device.createBond()) {
                    throw TransportException("could not start pairing")
                }
                awaitBond(beforeRequest)
                // During connect we are still enabling notifications; at runtime (a read/write on a
                // Ready link that the stack re-authenticated) go back to Ready, otherwise the UI
                // shows a stale "Enabling notifications…" banner and requireReady() fails.
                _state.value = if (before is ConnectionState.Ready) before else ConnectionState.EnablingNotifications
                continue
            }
            if (r.status == 5 || r.status == 15) {
                Log.w(TAG, "$name failed (GATT status ${r.status}): ${BleConnectionFailure.PAIRING_REPAIR_MESSAGE}")
                throw TransportException(BleConnectionFailure.PAIRING_REPAIR_MESSAGE, r.status)
            }
            return r
        }
    }

    private fun requireReady(): BluetoothGatt =
        gatt?.takeIf { _state.value.isReady } ?: throw TransportException("not connected")

    private suspend fun readCharacteristic(c: BluetoothGattCharacteristic?, name: String): ByteArray {
        val g = requireReady()
        val ch = c ?: missing(name)
        val r = opWithAuthRetry("read $name", GattCallbackKind.CHARACTERISTIC_READ, ch.uuid) { g.readCharacteristic(ch) }
        if (r.status != BluetoothGatt.GATT_SUCCESS) throw TransportException("read $name failed (GATT status ${r.status})", r.status)
        return r.value ?: ByteArray(0)
    }

    override suspend fun readInfo(): ByteArray = readCharacteristic(info, "info")
    override suspend fun readStatus(): ByteArray = readCharacteristic(status, "status")
    override suspend fun readLive(): ByteArray = readCharacteristic(live, "live")

    override suspend fun writeControl(request: ByteArray) = send(request, OP_TIMEOUT_MS.milliseconds)

    private suspend fun writeRequest(request: ByteArray) {
        require(request.size <= 512) { "control request is ${request.size} bytes; max 512" }
        val g = requireReady()
        val ch = control ?: missing("control")
        val r = opWithAuthRetry("write control", GattCallbackKind.CHARACTERISTIC_WRITE, ch.uuid) {
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

    override suspend fun disconnect() = fail(DisconnectCause.Deliberate("disconnect"))

    private fun teardown() {
        acceptConnectionCallbacks = false
        pendingOp.failAny(GattResult(GATT_DISCONNECTED))
        pendingConnect.getAndSet(null)?.resume(GATT_DISCONNECTED)
        gatt?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        gatt = null
        mtu = 23
    }

    override fun close() = fail(DisconnectCause.Deliberate("close"))

    private fun registerBondReceiver() {
        if (bondReceiverRegistered) return
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 36) filter.addAction(ACTION_KEY_MISSING)
        if (Build.VERSION.SDK_INT >= 33) {
            // EXPORTED on purpose. ACTION_BOND_STATE_CHANGED is sent by the Bluetooth app, a
            // privileged app that does not run as the system UID, and developer.android.com
            // ("Context-registered receivers") says a NOT_EXPORTED receiver misses broadcasts from
            // such apps. The action is protected, so another app cannot forge it.
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

    private fun Int.toBondState(): BleBondAwaiter.State = when (this) {
        BluetoothDevice.BOND_BONDING -> BleBondAwaiter.State.BONDING
        BluetoothDevice.BOND_BONDED -> BleBondAwaiter.State.BONDED
        else -> BleBondAwaiter.State.NONE
    }

    companion object {
        private const val TAG = "BleLinkDriver"

        /** `BluetoothDevice.ACTION_KEY_MISSING`, API 36. Protected, sent by the Bluetooth app. */
        const val ACTION_KEY_MISSING = "android.bluetooth.device.action.KEY_MISSING"
        const val GATT_DISCONNECTED = -1

        /** Consecutive stale bond closes per address, shared by every driver in this process. */
        private val staleBond = BleStaleBondDetector(enabled = Build.VERSION.SDK_INT < BleStaleBondDetector.KEY_MISSING_ACTION_API)

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
