package earth.walkthru.aqsync.link

import earth.walkthru.aqsync.data.ble.TransportKind
import earth.walkthru.aqsync.data.protocol.DeviceInfo
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration

/**
 * One physical byte pipe (a GATT link, a TCP socket or a scripted fake). The driver only moves
 * frames. Routing, timeouts and drains belong to [LinkSession].
 */
interface LinkDriver {
    val kind: TransportKind

    /**
     * Connect, authenticate and read `info`. Every frame, push and close from now on goes to
     * [sink], from any thread. Throws [LinkClosedException] when the link cannot be opened.
     */
    suspend fun open(sink: FrameSink): LinkInfo

    /** Write one request body to the peer within [deadline]. */
    suspend fun send(body: ByteArray, deadline: Duration)

    /** Tear the link down now. The driver reports [cause] through [FrameSink.onClosed]. */
    fun abort(cause: DisconnectCause)
}

/**
 * Where a driver's reader delivers. Calls never suspend and may come from any thread. A driver
 * calls [onClosed] exactly once, which [OnceSink] guarantees for any driver that wraps its sink.
 */
interface FrameSink {
    /** One complete `response` frame, type byte first. */
    fun onFrame(raw: ByteArray)

    /** One `status` or `live` notification body (UTF-8 JSON). */
    fun onPush(channel: PushChannel, json: ByteArray)

    /** Bytes arrived from the peer, framed or not. Feeds the heartbeat deadline. */
    fun onRxActivity()

    fun onClosed(cause: DisconnectCause)
}

enum class PushChannel { STATUS, LIVE }

/** What [LinkDriver.open] learned. [proto] is the version the peer reported, null when absent. */
data class LinkInfo(val kind: TransportKind, val payloadMax: Int, val info: DeviceInfo) {
    val proto: Int? get() = info.proto
}

/**
 * Makes a sink safe against racing closers: the first [onClosed] wins, later ones and every frame
 * after it are dropped. Drivers wrap the session's sink in this so abort and EOF can race freely.
 */
@OptIn(ExperimentalAtomicApi::class)
class OnceSink(private val delegate: FrameSink) : FrameSink {
    private val done = AtomicBoolean(false)

    val isClosed: Boolean get() = done.load()

    override fun onFrame(raw: ByteArray) {
        if (!done.load()) delegate.onFrame(raw)
    }

    override fun onPush(channel: PushChannel, json: ByteArray) {
        if (!done.load()) delegate.onPush(channel, json)
    }

    override fun onRxActivity() {
        if (!done.load()) delegate.onRxActivity()
    }

    override fun onClosed(cause: DisconnectCause) {
        if (done.compareAndSet(false, true)) delegate.onClosed(cause)
    }
}
