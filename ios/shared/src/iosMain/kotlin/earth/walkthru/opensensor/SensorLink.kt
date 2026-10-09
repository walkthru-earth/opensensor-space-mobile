package earth.walkthru.opensensor

import earth.walkthru.aqsync.data.ble.AqTransport
import earth.walkthru.aqsync.data.ble.ConnectionState
import earth.walkthru.aqsync.data.ble.TransportException
import earth.walkthru.aqsync.data.ble.TransportKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSLock
import kotlin.coroutines.CoroutineContext

/**
 * The byte pipe one Swift transport lends to the shared engine for one sync. Swift owns the socket
 * or the Core Bluetooth peripheral and stays the only code that touches it.
 *
 * - Requests leave through [send], always called on the main thread, one whole frame body at a
 *   time and in order. Over LAN Swift adds the length envelope. Over Bluetooth Swift writes it to
 *   `control` with response.
 * - Swift hands every response frame body to [deliver], from any thread, in arrival order. Status
 *   and live pushes stay with Swift.
 * - Swift calls [linkClosed] when the socket or the peripheral goes away. A pending request then
 *   fails at once instead of waiting for its timeout.
 *
 * A link serves one sync. Make a new one for the next sync.
 */
class SensorLink internal constructor(
    overWifi: Boolean,
    address: String,
    mtu: Int,
    private val send: (NSData) -> Unit,
    private val sendContext: CoroutineContext,
) {
    /** [address] is the hardware id. [mtu] is the Bluetooth ATT MTU, or the LAN frame cap. */
    constructor(overWifi: Boolean, address: String, mtu: Int, send: (NSData) -> Unit) :
        this(overWifi, address, mtu, send, Dispatchers.Main)

    private val frames = Channel<ByteArray>(Channel.UNLIMITED)
    private val state = MutableStateFlow<ConnectionState>(ConnectionState.Ready(mtu))

    /** One response frame body, copied once. Safe from any thread. Ignored after [linkClosed]. */
    fun deliver(frame: NSData) {
        frames.trySend(frame.toByteArray())
    }

    private val closeLock = NSLock()
    private var closed = false
    private val onClose = mutableListOf<() -> Unit>()

    /** The pipe is gone. Safe from any thread and more than once. */
    fun linkClosed() {
        state.value = ConnectionState.Disconnected
        frames.close()
        closeLock.lock()
        val callbacks = if (closed) emptyList() else onClose.toList()
        closed = true
        onClose.clear()
        closeLock.unlock()
        callbacks.forEach { it() }
    }

    /**
     * Runs [callback] once when the link closes, on the closing thread, or at once if it already
     * closed. A debug link lent to the Debug screen tells its Swift owner it is free again this way.
     */
    fun whenClosed(callback: () -> Unit) {
        closeLock.lock()
        val now = closed
        if (!now) onClose += callback
        closeLock.unlock()
        if (now) callback()
    }

    internal val transport: AqTransport = object : AqTransport {
        override val address: String = address
        override val displayName: String? = null
        override val kind: TransportKind = if (overWifi) TransportKind.LAN else TransportKind.BLE
        override val endpoint: String = address
        override val connectionState: StateFlow<ConnectionState> = state
        override val mtu: Int = mtu
        override val statusNotifications: Flow<ByteArray> = emptyFlow()
        override val liveNotifications: Flow<ByteArray> = emptyFlow()
        override val responseFrames: ReceiveChannel<ByteArray> = frames

        override suspend fun connect() = Unit

        override suspend fun disconnect() = linkClosed()

        override suspend fun readInfo(): ByteArray = throw TransportException("info is read by the native adapter")

        override suspend fun readStatus(): ByteArray = throw TransportException("status is read by the native adapter")

        override suspend fun readLive(): ByteArray = throw TransportException("live is read by the native adapter")

        override suspend fun writeControl(request: ByteArray) {
            if (state.value !is ConnectionState.Ready) throw TransportException("link closed")
            val data = request.toNSData()
            withContext(sendContext) { send(data) }
        }

        override fun close() = linkClosed()
    }
}
