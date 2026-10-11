package earth.walkthru.aqsync.data.lan

import earth.walkthru.aqsync.data.AndroidLink
import earth.walkthru.aqsync.data.ble.ConnectionState
import earth.walkthru.aqsync.data.ble.FrameRecorder
import earth.walkthru.aqsync.data.ble.TransportException
import earth.walkthru.aqsync.data.ble.TransportKind
import earth.walkthru.aqsync.data.protocol.DeviceInfo
import earth.walkthru.aqsync.data.protocol.Frame
import earth.walkthru.aqsync.data.protocol.FrameCodec
import earth.walkthru.aqsync.data.protocol.FrameDecodeException
import earth.walkthru.aqsync.data.protocol.FrameType
import earth.walkthru.aqsync.data.protocol.LAN_TOKEN_BYTES
import earth.walkthru.aqsync.data.protocol.LanFraming
import earth.walkthru.aqsync.data.protocol.Opcode
import earth.walkthru.aqsync.data.protocol.ProtocolError
import earth.walkthru.aqsync.link.AuthKind
import earth.walkthru.aqsync.link.DisconnectCause
import earth.walkthru.aqsync.link.FrameSink
import earth.walkthru.aqsync.link.LinkClosedException
import earth.walkthru.aqsync.link.LinkInfo
import earth.walkthru.aqsync.link.LinkTimeoutException
import earth.walkthru.aqsync.link.OnceSink
import earth.walkthru.aqsync.link.PushChannel
import earth.walkthru.aqsync.link.RequestSpec
import earth.walkthru.aqsync.link.TimeoutPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration

/**
 * One LAN connection (`ble-sync-protocol.md`, "LAN transport") as a [AndroidLink].
 *
 * [open] connects, sends `"AQS1"` + token unframed and waits for a framed `HELLO`. A reader on
 * [ioDispatcher] then hands every frame to the session's sink as it arrives. It never queues, so
 * a slow consumer is the session's budget problem, and the session's [Heartbeat][earth.walkthru.aqsync.link.Heartbeat]
 * is the only liveness rule. [send] is a single writer with a deadline. A write that cannot finish
 * closes the socket as `Timeout(SEND)`. Every end of the link reaches the sink once, typed.
 *
 * The token is copied in, sent once and zeroed. It is never logged.
 */
class TcpLinkDriver(
    override val address: String,
    override val displayName: String?,
    private val host: InetAddress,
    private val port: Int,
    token: ByteArray,
    private val ioDispatcher: CoroutineDispatcher,
    private val socketFactory: () -> Socket = ::Socket,
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    private val handshakeTimeoutMs: Int = HANDSHAKE_TIMEOUT_MS,
) : AndroidLink {

    init {
        require(token.size == LAN_TOKEN_BYTES) { "LAN token must be $LAN_TOKEN_BYTES bytes" }
    }

    private val token: ByteArray = token.copyOf()

    override val frames = FrameRecorder()

    override val kind: TransportKind = TransportKind.LAN
    override val endpoint: String get() = "${host.hostAddress}:$port"

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _state.asStateFlow()

    @Volatile
    private var payloadMax: Int = LanFraming.PAYLOAD_MAX
    override val mtu: Int get() = payloadMax + 3

    private val _status = MutableSharedFlow<ByteArray>(replay = 1, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val _live = MutableSharedFlow<ByteArray>(replay = 1, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val statusNotifications: Flow<ByteArray> = _status
    override val liveNotifications: Flow<ByteArray> = _live

    /** Not the frame path. Replies go to the session's sink. Closed and empty. */
    override val responseFrames: ReceiveChannel<ByteArray> = Channel<ByteArray>().also { it.close() }

    @Volatile
    private var hello: Frame.Hello? = null

    private val opened = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val writeLock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    @Volatile
    private var sink: OnceSink? = null

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var output: OutputStream? = null

    override suspend fun open(sink: FrameSink): LinkInfo {
        check(opened.compareAndSet(false, true)) { "a TcpLinkDriver opens once" }
        val once = OnceSink(sink)
        this.sink = once
        _state.value = ConnectionState.Connecting
        val (frame, input) = try {
            // Await on our own scope so a cancelled caller returns at once. Closing the socket
            // below is what unblocks the handshake thread.
            scope.async { handshake() }.await()
        } catch (e: LinkClosedException) {
            fail(e.disconnectCause)
            throw e
        } catch (e: Throwable) {
            // Cancellation lands here too. Nothing may keep the socket open.
            fail(DisconnectCause.Deliberate("open cancelled"))
            throw e
        } finally {
            token.fill(0)
        }
        if (closed.get()) throw LinkClosedException(DisconnectCause.Deliberate("aborted during open"), null)
        hello = frame
        payloadMax = frame.payloadMax
        _state.value = ConnectionState.Ready(mtu)
        scope.launch { readLoop(input, once) }
        val info = try {
            DeviceInfo.parse(frame.infoJson)
        } catch (e: Exception) {
            abort(DisconnectCause.FramingViolation("bad device info"))
            throw LinkClosedException(DisconnectCause.FramingViolation("bad device info"), null)
        }
        return LinkInfo(kind, payloadMax = frame.payloadMax, info = info)
    }

    /** Blocking. Returns the HELLO and the buffered input the reader continues from. */
    private fun handshake(): Pair<Frame.Hello, InputStream> {
        val s = socketFactory()
        socket = s
        try {
            s.tcpNoDelay = true
            s.keepAlive = true
            s.connect(InetSocketAddress(host, port), connectTimeoutMs)
        } catch (e: IOException) {
            throw LinkClosedException(SocketErrors.connectCause(e), LanConnectException(LanFailure.UNREACHABLE, null, e))
        }
        _state.value = ConnectionState.Authenticating
        val input = BufferedInputStream(s.getInputStream(), 8 * 1024)
        val out = BufferedOutputStream(s.getOutputStream(), 1024).also { output = it }
        try {
            out.write(LanFraming.handshake(token))
            out.flush()
        } catch (e: IOException) {
            throw LinkClosedException(SocketErrors.cause(e), LanConnectException(LanFailure.UNREACHABLE, null, e))
        }
        s.soTimeout = handshakeTimeoutMs
        val raw = try {
            LanFraming.read(input, LanFraming.PAYLOAD_MAX)
                ?: throw rejected("device closed the socket without HELLO")
        } catch (e: SocketTimeoutException) {
            throw LinkClosedException(DisconnectCause.Timeout(TimeoutPhase.HANDSHAKE, 0), LanConnectException(LanFailure.TIMEOUT, cause = e))
        } catch (e: EOFException) {
            throw rejected("device closed the socket during the handshake")
        } catch (e: FrameDecodeException) {
            throw protocol(e.message)
        } catch (e: IOException) {
            throw LinkClosedException(SocketErrors.cause(e), LanConnectException(LanFailure.UNREACHABLE, null, e))
        }
        val frame = try {
            FrameCodec.decode(raw)
        } catch (e: FrameDecodeException) {
            throw protocol(e.message)
        }
        when (frame) {
            is Frame.Hello -> if (frame.payloadMax < MIN_PAYLOAD_MAX) throw protocol("HELLO payload_max ${frame.payloadMax} is too small")
            is Frame.Error -> throw when (frame.code) {
                ProtocolError.AUTH_FAILED.code -> rejected(frame.describe())
                ProtocolError.BUSY.code -> LinkClosedException(DisconnectCause.Busy, LanConnectException(LanFailure.BUSY, frame.describe()))
                else -> protocol(frame.describe())
            }
            else -> throw protocol("expected HELLO, got ${frame::class.simpleName}")
        }
        // Quiet between pushes is allowed. The session heartbeat is the liveness rule.
        s.soTimeout = 0
        return frame as Frame.Hello to input
    }

    private fun rejected(detail: String) =
        LinkClosedException(DisconnectCause.Auth(AuthKind.TOKEN_REJECTED), LanConnectException(LanFailure.AUTH_FAILED, detail))

    private fun protocol(detail: String?) =
        LinkClosedException(DisconnectCause.FramingViolation("handshake"), LanConnectException(LanFailure.PROTOCOL, detail))

    private fun readLoop(input: InputStream, out: OnceSink) {
        val cause = try {
            while (true) {
                val raw = LanFraming.read(input, payloadMax) ?: break
                frames.rx(raw)
                out.onRxActivity()
                when (FrameCodec.typeOf(raw)) {
                    FrameType.STATUS -> raw.copyOfRange(1, raw.size).let { _status.tryEmit(it); out.onPush(PushChannel.STATUS, it) }
                    FrameType.LIVE -> raw.copyOfRange(1, raw.size).let { _live.tryEmit(it); out.onPush(PushChannel.LIVE, it) }
                    else -> out.onFrame(raw)
                }
            }
            DisconnectCause.PeerClosed()
        } catch (e: FrameDecodeException) {
            DisconnectCause.FramingViolation(if (e.message?.contains("exceeds") == true) "frame exceeds payload_max" else "bad frame length")
        } catch (e: IOException) {
            SocketErrors.cause(e)
        }
        fail(cause)
    }

    override suspend fun send(body: ByteArray, deadline: Duration) {
        val out = output
        if (out == null || closed.get()) throw LinkClosedException(DisconnectCause.LocalAbort(), null)
        val op = body.firstOrNull()?.toInt()?.and(0xFF) ?: 0
        writeLock.withLock {
            if (closed.get()) throw LinkClosedException(DisconnectCause.LocalAbort(), null)
            val write = scope.async { LanFraming.write(out, body) }
            val done = try {
                withTimeoutOrNull(deadline) { write.await() }
            } catch (e: CancellationException) {
                if (!currentCoroutineContext().isActive) {
                    // The caller cancelled while bytes may still be going out. A half sent frame
                    // cannot be followed by the CLOSE the session owes, so end the link as on a timeout.
                    if (!write.isCompleted) {
                        fail(DisconnectCause.LocalAbort())
                        write.cancel()
                    }
                    throw e
                }
                // The link closed under the write.
                throw LinkClosedException(DisconnectCause.LocalAbort(), null)
            } catch (e: IOException) {
                val cause = SocketErrors.cause(e)
                fail(cause)
                throw LinkClosedException(cause, TransportException("LAN write failed", cause = e))
            }
            if (done == null) {
                // A blocked socket write cannot be cancelled, only closed under it.
                val cause = DisconnectCause.Timeout(TimeoutPhase.SEND, op)
                fail(cause)
                write.cancel()
                throw LinkTimeoutException(cause)
            }
            frames.tx(body)
        }
    }

    override fun abort(cause: DisconnectCause) = fail(cause)

    /** Reports [cause] once, then closes the socket. Safe from any thread, any number of times. */
    private fun fail(cause: DisconnectCause) {
        sink?.onClosed(cause)
        if (!closed.compareAndSet(false, true)) return
        _state.value = when (cause) {
            is DisconnectCause.Deliberate, is DisconnectCause.PeerClosed -> ConnectionState.Disconnected
            else -> ConnectionState.Failed(cause::class.simpleName ?: "closed")
        }
        runCatching { socket?.close() }
        scope.cancel()
    }

    // ------------------------------------------------------- AqTransport view

    override suspend fun connect() {
        if (!_state.value.isReady) throw TransportException("open the link session, not the transport")
    }

    override suspend fun disconnect() = abort(DisconnectCause.Deliberate("disconnect"))

    override fun close() = abort(DisconnectCause.Deliberate("close"))

    override suspend fun readInfo(): ByteArray =
        hello?.infoJson?.encodeToByteArray() ?: throw TransportException("not connected")

    override suspend fun readStatus(): ByteArray {
        if (!_state.value.isReady) throw TransportException("not connected")
        _status.replayCache.firstOrNull()?.let { return it }
        send(byteArrayOf(Opcode.STATUS.toByte()), RequestSpec.WRITE_DEADLINE)
        return withTimeoutOrNull(PUSH_WAIT_MS) { _status.first() }
            ?: throw TransportException("no STATUS push within ${PUSH_WAIT_MS / 1000} s")
    }

    override suspend fun readLive(): ByteArray {
        if (!_state.value.isReady) throw TransportException("not connected")
        _live.replayCache.firstOrNull()?.let { return it }
        return withTimeoutOrNull(LIVE_WAIT_MS) { _live.first() }
            ?: throw TransportException("no LIVE push within ${LIVE_WAIT_MS / 1000} s")
    }

    override suspend fun writeControl(request: ByteArray) = send(request, RequestSpec.WRITE_DEADLINE)

    companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
        const val HANDSHAKE_TIMEOUT_MS = 8_000
        const val MIN_PAYLOAD_MAX = 64
        const val PUSH_WAIT_MS = 10_000L
        const val LIVE_WAIT_MS = 15_000L
    }
}
