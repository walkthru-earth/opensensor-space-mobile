package earth.walkthru.opensensor

import earth.walkthru.aqsync.data.ble.AqTransport
import earth.walkthru.aqsync.data.ble.ConnectionState
import earth.walkthru.aqsync.data.ble.DeviceSession
import earth.walkthru.aqsync.data.ble.FrameRecorder
import earth.walkthru.aqsync.data.ble.TransportException
import earth.walkthru.aqsync.data.ble.TransportKind
import earth.walkthru.aqsync.data.protocol.AqGatt
import earth.walkthru.aqsync.data.protocol.DeviceInfo
import earth.walkthru.aqsync.link.DisconnectCause
import earth.walkthru.aqsync.link.FrameSink
import earth.walkthru.aqsync.link.InboxWatch
import earth.walkthru.aqsync.link.LinkClosedException
import earth.walkthru.aqsync.link.LinkDriver
import earth.walkthru.aqsync.link.LinkInfo
import earth.walkthru.aqsync.link.LinkSession
import earth.walkthru.aqsync.link.LinkState
import earth.walkthru.aqsync.link.OnceSink
import earth.walkthru.aqsync.link.PushChannel
import earth.walkthru.aqsync.link.Radio
import earth.walkthru.aqsync.link.TimeoutPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSData
import platform.Foundation.NSLock
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.time.Duration

/**
 * The Swift side of one physical connection, a Core Bluetooth peripheral or a LAN socket. Swift
 * owns it and stays the only code that touches the radio.
 *
 * Calls come from any thread. None of them may block or call back into [IosLink] before returning.
 */
interface IosLinkPort {
    /** The Bluetooth ATT MTU for writes with response, or the LAN frame cap plus 3. */
    val mtu: Int

    /**
     * Write one request body (Swift adds the LAN length envelope). Call [done] exactly once, from any
     * thread, with true once the peer took the bytes and false when the write failed.
     */
    fun send(frame: NSData, done: (Boolean) -> Unit)

    /**
     * Stop (true) or resume (false) reading from the peer. Calls alternate and arrive in order, so a
     * serial queue keeps them in order. A Bluetooth link cannot stop notifications and may ignore it,
     * since its replies are bounded by the read window it asked for.
     */
    fun setReadPaused(paused: Boolean)
}

/** What a lease is for. Two leases never overlap on one [IosLink]. */
internal enum class LeaseKind { SYNC, COMMAND, DEBUG, CONFIG, FILES, NATIVE }

/** A lease was refused at once. [syncRunning] when a sync holds the link, else the link is closed. */
internal class LeaseRefusedException(val syncRunning: Boolean) : Exception(if (syncRunning) "a sync holds the link" else "link closed")

/**
 * One physical connection's protocol state, made by Swift right after the link authenticated and kept
 * until it closes. It wraps one [LinkSession], so a reply abandoned by one run is drained, and any
 * owed `CLOSE` sent, before the next run's first request. Sync, commands, Debug and Device settings
 * each take a lease of it. Ending a lease never closes the link, Swift decides whether to keep it.
 *
 * - Swift hands every `response` frame body to [offer] in arrival order, from any thread. False asks
 *   Swift to stop reading, [IosLinkPort.setReadPaused] says when to go on.
 * - Status pushes go to [offerStatus].
 * - Swift calls [linkLost] with a reason code when the socket or peripheral goes away. Frames offered
 *   before it still reach their callers.
 * - A write that fails or never completes, an inbox overflow or a drain past its bound poisons the
 *   link. It refuses leases from then on, [whenClosed] fires and Swift reconnects with a new one.
 */
@OptIn(ExperimentalAtomicApi::class)
class IosLink internal constructor(
    private val port: IosLinkPort,
    overWifi: Boolean,
    /** The hardware id, which keys the Debug frame history. */
    val address: String,
    private val callbacks: CoroutineContext,
    context: CoroutineContext,
    inboxBudget: Long = LinkSession.defaultBudget(if (overWifi) TransportKind.LAN else TransportKind.BLE),
) {
    constructor(port: IosLinkPort, overWifi: Boolean, address: String) :
        this(port, overWifi, address, Dispatchers.Main, Dispatchers.Default)

    val kind: TransportKind = if (overWifi) TransportKind.LAN else TransportKind.BLE

    /** [IosLinkPort.mtu], for the Debug link line. */
    val mtu: Int get() = port.mtu

    internal var station: String? = null
        private set
    internal var boot: String? = null
        private set

    /** The sensor's advertised read window, for [IosLinkProvider]. */
    internal var maxRead = 0
        private set

    /** Bind the full boot identity from authenticated INFO or HELLO before anchoring time. */
    fun identify(station: String, boot: String?, maxRead: Int) {
        this.station = station
        this.boot = boot
        this.maxRead = maxRead
    }

    /** Whether the automatic clock check already ran on this connection. Read and set under a lease. */
    internal var clockChecked = false

    private val scope = CoroutineScope(SupervisorJob() + context)
    private val recorder: FrameRecorder = debugFrames.recorderFor(address)
    private val driver = Driver()

    /** Pause at half the budget, so frames already on the way still fit, and resume below a quarter. */
    private val pauseAt = inboxBudget / 2
    private val pauseLock = NSLock()
    private var paused = false

    // Declared before [session], whose constructor already collects it.
    private val statusPushes = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val link = LinkSession(driver, scope, inboxBudget = inboxBudget, inboxWatch = InboxWatch(::onInboxLevel))
    internal val session = DeviceSession(link, View(), recorder)

    private val opened = CompletableDeferred<Unit>()
    private val leaseLock = Mutex()
    private val holder = AtomicReference<LeaseKind?>(null)

    private val closeLock = NSLock()
    private var closedFired = false
    private val onClose = mutableListOf<() -> Unit>()

    init {
        // The driver opens without suspending, so the sink exists before Swift's first offer.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                link.open()
                opened.complete(Unit)
            } catch (e: Throwable) {
                opened.completeExceptionally(e)
            }
        }
        link.closed.invokeOnCompletion { scope.launch { closeCallbacks() } }
    }

    /** False once the link closed or was poisoned. Swift then makes a new link for the next connection. */
    val isOpen: Boolean get() = !link.closed.isCompleted

    /**
     * One `response` frame body, copied once. Returns false when the inbox is at its high mark, which
     * also calls [IosLinkPort.setReadPaused] true once. Ignored after the link closed.
     */
    fun offer(frame: NSData): Boolean {
        val raw = frame.toByteArray()
        if (raw.isNotEmpty()) recorder.rx(raw)
        driver.sink.load()?.onFrame(raw)
        pauseLock.lock()
        val accepting = !paused
        pauseLock.unlock()
        return accepting
    }

    /** A status push. The newest one wins, and the shared clock policy reads it. */
    fun offerStatus(json: String) {
        val bytes = json.encodeToByteArray()
        driver.sink.load()?.onPush(PushChannel.STATUS, bytes)
        statusPushes.tryEmit(bytes)
    }

    /** The socket or peripheral is gone, one of the `REASON_` codes. Safe from any thread and more than once. */
    fun linkLost(reason: Int) {
        driver.sink.load()?.onClosed(causeOf(reason))
    }

    /** Close on purpose, for example when Swift disconnects. Safe from any thread and more than once. */
    fun close() {
        link.close("native close")
    }

    /**
     * Runs [callback] once when the link closes or is poisoned, on a background thread, or at once
     * if it already closed. Swift tears its side down there when it still has it.
     */
    fun whenClosed(callback: () -> Unit) {
        closeLock.lock()
        val now = closedFired
        if (!now) onClose += callback
        closeLock.unlock()
        if (now) callback()
    }

    private fun closeCallbacks() {
        closeLock.lock()
        val list = if (closedFired) emptyList() else onClose.toList()
        closedFired = true
        onClose.clear()
        closeLock.unlock()
        list.forEach { it() }
    }

    /**
     * Native work that writes its own request, today only the Bluetooth `GET_TOKEN` whose reply Swift
     * keeps, so the token never reaches Kotlin. [granted] runs on the main thread with a lease once
     * the link is free, or with null when a sync holds it or it closed. Swift must call
     * [IosNativeLease.end] once its reply arrived or failed.
     */
    fun leaseForNative(granted: (IosNativeLease?) -> Unit) {
        scope.launch {
            val lease = try {
                acquire(LeaseKind.NATIVE)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            val handed = lease?.let(::IosNativeLease)
            try {
                withContext(callbacks) { granted(handed) }
            } catch (e: CancellationException) {
                handed?.end()
                throw e
            }
        }
    }

    /** True while a sync holds the link, for Swift's own guards. */
    val syncRunning: Boolean get() = holder.load() == LeaseKind.SYNC

    /** True while any lease holds the link. */
    val leased: Boolean get() = holder.load() != null

    private val leaseListener = AtomicReference<((Boolean) -> Unit)?>(null)

    /**
     * [listener] hears [leased] on the main thread after each lease starts or ends, so Swift can hold
     * an idle close or a queued step while Kotlin uses the link.
     */
    fun onLeaseChange(listener: (Boolean) -> Unit) {
        leaseListener.store(listener)
    }

    private fun leaseChanged() {
        val listener = leaseListener.load() ?: return
        scope.launch(callbacks) { listener(leased) }
    }

    // ---- leases ----

    /**
     * Waits for the link, behind the lease before it and any owed work, then hands it out. A second
     * sync is refused at once, so is anything but a command while a sync runs. A cancel while waiting
     * takes nothing, a cancel after the lock was taken gives it back.
     */
    internal suspend fun acquire(kind: LeaseKind): Lease {
        if (!isOpen) throw LeaseRefusedException(syncRunning = false)
        if (holder.load() == LeaseKind.SYNC && kind != LeaseKind.COMMAND) throw LeaseRefusedException(syncRunning = true)
        leaseLock.lock()
        val lease = Lease(kind)
        holder.store(kind)
        leaseChanged()
        try {
            opened.await()
            if (!isOpen) throw LinkClosedException(link.closed.getCompleted())
        } catch (e: Throwable) {
            lease.end()
            throw e
        }
        return lease
    }

    /** Runs [block] on a lease and always gives it back. */
    internal suspend fun <T> leased(kind: LeaseKind, block: suspend (DeviceSession) -> T): T {
        val lease = acquire(kind)
        try {
            return block(session)
        } finally {
            lease.end()
        }
    }

    /** A held lease. [end] gives it back once, later calls do nothing. */
    internal inner class Lease(val kind: LeaseKind) {
        private val ended = AtomicBoolean(false)

        fun end() {
            if (!ended.compareAndSet(false, true)) return
            holder.compareAndSet(kind, null)
            leaseLock.unlock()
            leaseChanged()
        }
    }

    // ---- backpressure ----

    internal fun onInboxLevel(bytes: Long) {
        pauseLock.lock()
        try {
            val change = when {
                !paused && bytes >= pauseAt -> true
                paused && bytes < pauseAt / 2 -> false
                else -> return
            }
            paused = change
            // Under the lock, so Swift sees pause and resume in the order they were decided.
            port.setReadPaused(change)
        } finally {
            pauseLock.unlock()
        }
    }

    // ---- driver ----

    private inner class Driver : LinkDriver {
        override val kind: TransportKind get() = this@IosLink.kind
        val sink = AtomicReference<OnceSink?>(null)

        override suspend fun open(sink: FrameSink): LinkInfo {
            this.sink.store(OnceSink(sink))
            // Swift refused a sensor below proto 3 before making the link (`SyncWire.identity`).
            return LinkInfo(kind, payloadMax = port.mtu - 3, info = DeviceInfo(proto = AqGatt.PROTOCOL_VERSION, station = station, boot = boot))
        }

        override suspend fun send(body: ByteArray, deadline: Duration) {
            recorder.tx(body)
            val data = body.toNSData()
            val written = withTimeoutOrNull(deadline) {
                suspendCancellableCoroutine { cont ->
                    val once = AtomicBoolean(false)
                    port.send(data) { ok -> if (once.compareAndSet(false, true)) cont.resume(ok) }
                }
            }
            if (written == true) return
            // The device may or may not hold the request now, so nothing later on this link can be trusted.
            val cause = if (written == null) DisconnectCause.Timeout(TimeoutPhase.SEND, body[0].toInt() and 0xFF) else DisconnectCause.LocalAbort()
            sink.load()?.onClosed(cause)
            throw LinkClosedException(cause)
        }

        override fun abort(cause: DisconnectCause) {
            sink.load()?.onClosed(cause)
        }
    }

    /** What [DeviceSession] still reads beside the link. Replies never pass through it. */
    private inner class View : AqTransport {
        override val address: String = this@IosLink.address
        override val displayName: String? = null
        override val kind: TransportKind = this@IosLink.kind
        override val endpoint: String = this@IosLink.address
        override val connectionState: StateFlow<ConnectionState> = MutableStateFlow(ConnectionState.Ready(port.mtu))
        override val mtu: Int = port.mtu
        override val statusNotifications: Flow<ByteArray> = statusPushes
        override val liveNotifications: Flow<ByteArray> = emptyFlow()
        override val responseFrames: ReceiveChannel<ByteArray> = Channel<ByteArray>().also { it.close() }

        override suspend fun connect() = Unit

        override suspend fun disconnect() = close()

        override suspend fun readInfo(): ByteArray = throw TransportException("info is read by the native adapter")

        /** The newest status push on this link. Its UTC is never used to measure drift. */
        override suspend fun readStatus(): ByteArray = link.status.value ?: throw TransportException("no status pushed yet")

        override suspend fun readLive(): ByteArray = throw TransportException("live is read by the native adapter")

        override suspend fun writeControl(request: ByteArray) = throw TransportException("requests go through the link session")

        override fun close() = this@IosLink.close()
    }

    companion object {
        /** The peer closed the stream or the peripheral disconnected. */
        const val REASON_PEER_CLOSED = 0

        /** The peer reset the socket. */
        const val REASON_RESET = 1

        /** Bluetooth or Wi-Fi went away on the phone. */
        const val REASON_RADIO_BLUETOOTH = 2
        const val REASON_RADIO_WIFI = 3

        /** The phone's own stack failed, for example a write or the liveness deadline. */
        const val REASON_LOCAL = 4

        /** The app closed it, for example on suspend. */
        const val REASON_APP = 5

        internal fun causeOf(reason: Int): DisconnectCause = when (reason) {
            REASON_PEER_CLOSED -> DisconnectCause.PeerClosed()
            REASON_RESET -> DisconnectCause.PeerReset
            REASON_RADIO_BLUETOOTH -> DisconnectCause.RadioLost(Radio.BLUETOOTH)
            REASON_RADIO_WIFI -> DisconnectCause.RadioLost(Radio.WIFI)
            REASON_APP -> DisconnectCause.Deliberate("native close")
            else -> DisconnectCause.LocalAbort()
        }
    }
}

/** A lease Swift holds for its own request. [end] is safe from any thread and more than once. */
class IosNativeLease internal constructor(private val lease: IosLink.Lease) {
    fun end() = lease.end()
}
