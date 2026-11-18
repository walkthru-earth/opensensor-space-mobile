package space.opensensor.data.lan

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
import space.opensensor.data.AndroidLink
import space.opensensor.data.ble.ConnectionState
import space.opensensor.data.ble.FrameRecorder
import space.opensensor.data.ble.TransportException
import space.opensensor.data.ble.TransportKind
import space.opensensor.data.protocol.DeviceGatt
import space.opensensor.data.protocol.DeviceInfo
import space.opensensor.data.protocol.Frame
import space.opensensor.data.protocol.FrameCodec
import space.opensensor.data.protocol.FrameDecodeException
import space.opensensor.data.protocol.FrameType
import space.opensensor.data.protocol.LAN_TOKEN_BYTES
import space.opensensor.data.protocol.LanFraming
import space.opensensor.data.protocol.Opcode
import space.opensensor.data.protocol.ProtocolError
import space.opensensor.link.AuthKind
import space.opensensor.link.DisconnectCause
import space.opensensor.link.FrameSink
import space.opensensor.link.LinkClosedException
import space.opensensor.link.LinkInfo
import space.opensensor.link.LinkTimeoutException
import space.opensensor.link.OnceSink
import space.opensensor.link.PushChannel
import space.opensensor.link.RequestSpec
import space.opensensor.link.TimeoutPhase
import space.opensensor.link.peerClosedCause
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
 * [open] connects, sends `"OSS1"` + token unframed and waits for a framed `HELLO`. A reader on
 * [ioDispatcher] then hands every frame to the session's sink as it arrives. It never queues, so
 * a slow consumer is the session's budget problem, and the session's [Heartbeat][space.opensensor.link.Heartbeat]
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
    private var payloadMax: Int = LanHandshake.PAYLOAD_MAX
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
        val (frame, info, input) = try {
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
        return LinkInfo(kind, payloadMax = frame.payloadMax, info = info)
    }

    private data class Greeting(val hello: Frame.Hello, val info: DeviceInfo, val input: InputStream)

    /**
     * Blocking. Returns the HELLO accepted by the shared [LanHandshake.check], its info, and the
     * buffered input the reader continues from. The saved identity is checked by the caller
     * through the same `DeviceContinuityPolicy` that [LanHandshake.verify] uses.
     */
    private fun handshake(): Greeting {
        val s = socketFactory()
        socket = s
        try {
            s.tcpNoDelay = true
            s.keepAlive = true
            s.connect(InetSocketAddress(host, port), LanHandshake.CONNECT_TIMEOUT.inWholeMilliseconds.toInt())
        } catch (e: IOException) {
            throw LinkClosedException(SocketErrors.connectCause(e), SocketErrors.connectException(e))
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
        s.soTimeout = LanHandshake.HELLO_TIMEOUT.inWholeMilliseconds.toInt()
        val raw = try {
            LanFraming.read(input, LanHandshake.PAYLOAD_MAX)
                ?: throw peerClosed("device closed the socket without HELLO")
        } catch (e: SocketTimeoutException) {
            throw LinkClosedException(DisconnectCause.Timeout(TimeoutPhase.HANDSHAKE, 0), LanConnectException(LanFailure.TIMEOUT, cause = e))
        } catch (e: EOFException) {
            // A bare close is not an auth verdict (a reboot or a Wi-Fi drop looks the same). Only
            // ERROR 14 or BYE auth-failed (3) rejects the token, so the stored token survives.
            throw peerClosed("device closed the socket during the handshake")
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
            is Frame.Error -> throw when (frame.code) {
                ProtocolError.AUTH_FAILED.code -> rejected(frame.describe())
                ProtocolError.BUSY.code -> LinkClosedException(DisconnectCause.Busy, LanConnectException(LanFailure.BUSY, frame.describe()))
                else -> protocol(frame.describe())
            }
            is Frame.Bye -> throw byeClosed(frame)
            else -> Unit
        }
        when (val verdict = LanHandshake.check(frame)) {
            is LanHandshake.Verdict.Accepted -> {
                // Quiet between pushes is allowed. The session heartbeat is the liveness rule.
                s.soTimeout = 0
                return Greeting(verdict.hello, verdict.info, input)
            }
            // The same cause LinkSession gives an old INFO, so the owner shows the update notice.
            is LanHandshake.Verdict.OldProto -> throw LinkClosedException(
                DisconnectCause.UpdateFirmware(verdict.reported, DeviceGatt.PROTOCOL_VERSION),
                LanConnectException(LanFailure.PROTOCOL, "proto ${verdict.reported}"),
            )
            is LanHandshake.Verdict.PayloadCapOutOfRange -> throw protocol("HELLO payload_max ${verdict.payloadMax} is outside ${LanHandshake.MIN_PAYLOAD_MAX}..${LanHandshake.PAYLOAD_MAX}")
            LanHandshake.Verdict.BadInfo -> throw LinkClosedException(DisconnectCause.FramingViolation("bad device info"), LanConnectException(LanFailure.PROTOCOL, "bad device info"))
            LanHandshake.Verdict.WrongKind -> throw protocol("expected HELLO, got ${frame::class.simpleName}")
            // Identity verdicts come from LanHandshake.verify, which this driver does not call.
            LanHandshake.Verdict.DeviceChanged, LanHandshake.Verdict.ThingChanged -> throw protocol("unexpected identity verdict")
        }
    }

    private fun rejected(detail: String) =
        LinkClosedException(DisconnectCause.Auth(AuthKind.TOKEN_REJECTED), LanConnectException(LanFailure.AUTH_FAILED, detail))

    /** A BYE at the handshake keeps its typed cause, so a rotated token (10) or full sessions (4) read as such. */
    private fun byeClosed(bye: Frame.Bye): LinkClosedException {
        val cause = peerClosedCause(bye)
        val failure = when (cause) {
            is DisconnectCause.Auth -> LanFailure.AUTH_FAILED
            DisconnectCause.Busy -> LanFailure.BUSY
            else -> LanFailure.UNREACHABLE
        }
        return LinkClosedException(cause, LanConnectException(failure, "BYE ${bye.disconnectReason.wireName}"))
    }

    private fun peerClosed(detail: String) =
        LinkClosedException(DisconnectCause.PeerClosed(), LanConnectException(LanFailure.UNREACHABLE, detail))

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

    // ------------------------------------------------------- DeviceTransport view

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
        return withTimeoutOrNull(LanHandshake.STATUS_PUSH_WAIT) { _status.first() }
            ?: throw TransportException("no STATUS push within ${LanHandshake.STATUS_PUSH_WAIT}")
    }

    override suspend fun readLive(): ByteArray {
        if (!_state.value.isReady) throw TransportException("not connected")
        _live.replayCache.firstOrNull()?.let { return it }
        return withTimeoutOrNull(LanHandshake.LIVE_PUSH_WAIT) { _live.first() }
            ?: throw TransportException("no LIVE push within ${LanHandshake.LIVE_PUSH_WAIT}")
    }

    override suspend fun writeControl(request: ByteArray) = send(request, RequestSpec.WRITE_DEADLINE)
}
