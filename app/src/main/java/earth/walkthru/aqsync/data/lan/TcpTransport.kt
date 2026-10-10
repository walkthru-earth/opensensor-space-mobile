package earth.walkthru.aqsync.data.lan

import android.util.Log
import earth.walkthru.aqsync.data.ble.AqTransport
import earth.walkthru.aqsync.data.ble.ConnectionState
import earth.walkthru.aqsync.data.ble.TransportException
import earth.walkthru.aqsync.data.ble.TransportKind
import earth.walkthru.aqsync.data.coroutines.suspendRunCatching
import earth.walkthru.aqsync.data.protocol.Frame
import earth.walkthru.aqsync.data.protocol.FrameCodec
import earth.walkthru.aqsync.data.protocol.FrameDecodeException
import earth.walkthru.aqsync.data.protocol.FrameType
import earth.walkthru.aqsync.data.protocol.LAN_TOKEN_BYTES
import earth.walkthru.aqsync.data.protocol.LanFraming
import earth.walkthru.aqsync.data.protocol.Opcode
import earth.walkthru.aqsync.data.protocol.ProtocolError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
import java.util.concurrent.atomic.AtomicLong

/**
 * [AqTransport] over one plain TCP socket (docs/ble-sync-protocol.md, "LAN transport").
 *
 * connect → `"AQS1"` + token (unframed) → framed `HELLO` (or `ERROR` 14 / 7) → Ready. From then on a
 * single reader loop takes `len` u16 LE frames off the socket and dispatches on the type byte:
 * `0x48 STATUS` → [statusNotifications], `0x49 LIVE` → [liveNotifications], everything else →
 * [responseFrames] in order, none dropped. Pushes may sit between any two response frames; the
 * protocol layer never sees them. Requests are length-prefixed writes.
 *
 * [mtu] reports `payload_max + 3` so the existing `payload_max = mtu − 3` arithmetic yields the
 * value from `HELLO` (1024 on today's firmware). The socket is unencrypted by contract; the token is
 * a bearer secret and is never logged.
 */
internal data class TcpLiveness(
    val pollMs: Long = TcpTransport.KEEPALIVE_POLL_MS,
    val idleMs: Long = LanFraming.KEEPALIVE_AFTER_IDLE_MS,
    val deadMs: Long = TcpTransport.DEAD_AFTER_MS,
)

class TcpTransport internal constructor(
    override val address: String,
    override val displayName: String?,
    private val host: InetAddress,
    private val port: Int,
    private val token: ByteArray,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val liveness: TcpLiveness = TcpLiveness(),
) : AqTransport {

    init {
        require(token.size == LAN_TOKEN_BYTES) { "LAN token must be $LAN_TOKEN_BYTES bytes" }
    }

    override val kind: TransportKind = TransportKind.LAN
    override val endpoint: String get() = "${host.hostAddress}:$port"

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _state.asStateFlow()

    @Volatile
    private var payloadMax: Int = LanFraming.PAYLOAD_MAX
    override val mtu: Int get() = payloadMax + 3

    private val _status = MutableSharedFlow<ByteArray>(replay = 1, extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val _live = MutableSharedFlow<ByteArray>(replay = 1, extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val statusNotifications: Flow<ByteArray> = _status
    override val liveNotifications: Flow<ByteArray> = _live

    // Bound retained replies; suspension lets TCP backpressure reach the sensor instead of
    // accumulating an unlimited manifest/transfer in the phone's heap. Frames are never dropped.
    private val _responses = Channel<ByteArray>(RESPONSE_CAPACITY)
    override val responseFrames: ReceiveChannel<ByteArray> = _responses

    /** `HELLO.infoJson`, the same document as the `info` characteristic. */
    @Volatile
    var hello: Frame.Hello? = null
        private set

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val connectMutex = Mutex()
    private val writeMutex = Mutex()

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var input: InputStream? = null

    @Volatile
    private var output: OutputStream? = null
    private var readerJob: Job? = null
    private var keepaliveJob: Job? = null

    private val lastTxAt = AtomicLong(0)
    private val lastRxAt = AtomicLong(0)

    /** Set while we are closing on purpose, so the reader's IOException is not reported as a lost link. */
    @Volatile
    private var closing = false

    // ---------------------------------------------------------------- connect

    override suspend fun connect(): Unit = connectMutex.withLock {
        if (_state.value.isReady) return
        _state.value = ConnectionState.Connecting
        closing = false
        try {
            withContext(Dispatchers.IO) {
                val s = Socket()
                socket = s
                try {
                    s.tcpNoDelay = true
                    s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                } catch (e: IOException) {
                    throw LanConnectException(LanFailure.UNREACHABLE, e.message, e)
                }
                val inp = BufferedInputStream(s.getInputStream(), 8 * 1024).also { input = it }
                val out = BufferedOutputStream(s.getOutputStream(), 1024).also { output = it }

                _state.value = ConnectionState.Authenticating
                // The handshake is the one unframed message.
                out.write(LanFraming.handshake(token))
                out.flush()
                lastTxAt.set(System.currentTimeMillis())

                s.soTimeout = HANDSHAKE_TIMEOUT_MS
                val raw = try {
                    LanFraming.read(inp, LanFraming.PAYLOAD_MAX)
                        ?: throw LanConnectException(LanFailure.AUTH_FAILED, "device closed the socket without HELLO")
                } catch (e: SocketTimeoutException) {
                    throw LanConnectException(LanFailure.TIMEOUT, cause = e)
                } catch (e: EOFException) {
                    throw LanConnectException(LanFailure.AUTH_FAILED, "device closed the socket during the handshake", e)
                } catch (e: FrameDecodeException) {
                    throw LanConnectException(LanFailure.PROTOCOL, e.message, e)
                }
                val frame = try {
                    FrameCodec.decode(raw)
                } catch (e: FrameDecodeException) {
                    throw LanConnectException(LanFailure.PROTOCOL, e.message, e)
                }
                when (frame) {
                    is Frame.Hello -> {
                        if (frame.payloadMax < MIN_PAYLOAD_MAX) {
                            throw LanConnectException(LanFailure.PROTOCOL, "HELLO payload_max ${frame.payloadMax} is too small")
                        }
                        hello = frame
                        payloadMax = frame.payloadMax
                    }
                    is Frame.Error -> throw LanConnectException(
                        when (frame.code) {
                            ProtocolError.AUTH_FAILED.code -> LanFailure.AUTH_FAILED
                            ProtocolError.BUSY.code -> LanFailure.BUSY
                            else -> LanFailure.PROTOCOL
                        },
                        frame.describe(),
                    )
                    else -> throw LanConnectException(LanFailure.PROTOCOL, "expected HELLO, got ${frame::class.simpleName}")
                }
                // No read timeout from here on: the device is allowed to be quiet between pushes.
                // Liveness comes from the keep-alive ping and a receive deadline instead.
                s.soTimeout = 0
                lastRxAt.set(System.currentTimeMillis())
            }
            _state.value = ConnectionState.Ready(mtu)
            // Publish readiness before either child runs: an eager keepalive must not exit while
            // still Authenticating, and an eager reader failure must not be overwritten by Ready.
            readerJob = scope.launch { readerLoop() }
            keepaliveJob = scope.launch { keepaliveLoop() }
            Log.i(TAG, "ready: $endpoint payload_max=$payloadMax proto=${hello?.proto}")
        } catch (e: CancellationException) {
            closeSocket()
            _state.value = ConnectionState.Disconnected
            throw e
        } catch (e: LanConnectException) {
            closeSocket()
            _state.value = ConnectionState.Failed(e.message ?: e.failure.label)
            throw e
        } catch (e: Exception) {
            closeSocket()
            val wrapped = LanConnectException(LanFailure.UNREACHABLE, e.message ?: e::class.simpleName, e)
            _state.value = ConnectionState.Failed(wrapped.message ?: "connection failed")
            throw wrapped
        }
    }

    // ------------------------------------------------------------ reader loop

    private suspend fun readerLoop() {
        val inp = input ?: return
        try {
            while (scope.isActive) {
                val raw = LanFraming.read(inp, payloadMax) ?: break // clean EOF: device closed
                lastRxAt.set(System.currentTimeMillis())
                dispatch(raw)
            }
            linkLost("device closed the connection")
        } catch (e: FrameDecodeException) {
            // Lost framing: nothing after this byte can be trusted. Drop the link.
            linkLost("LAN framing error: ${e.message}")
        } catch (e: IOException) {
            if (_state.value.isReady) linkLost("socket error: ${e.message}")
        }
    }

    private suspend fun dispatch(raw: ByteArray) {
        when (FrameCodec.typeOf(raw)) {
            FrameType.STATUS -> _status.tryEmit(raw.copyOfRange(1, raw.size))
            FrameType.LIVE -> _live.tryEmit(raw.copyOfRange(1, raw.size))
            else -> _responses.send(raw)
        }
    }

    /**
     * The device drops a session after 300 s without bytes from the phone. Send `STATUS` (0x07) when we
     * have been quiet for 120 s; it costs one push frame. If nothing at all arrives for
     * [DEAD_AFTER_MS] the socket is treated as gone even though TCP has not said so yet.
     */
    private suspend fun keepaliveLoop() {
        while (scope.isActive && _state.value.isReady) {
            delay(liveness.pollMs)
            val now = System.currentTimeMillis()
            if (now - lastRxAt.get() > liveness.deadMs) {
                linkLost("no data from the device for ${liveness.deadMs / 1000} s")
                return
            }
            if (now - lastTxAt.get() > liveness.idleMs) {
                suspendRunCatching { writeControl(byteArrayOf(Opcode.STATUS.toByte())) }
                    .onFailure { Log.w(TAG, "keep-alive failed: ${it.message}") }
            }
        }
    }

    private fun linkLost(reason: String) {
        if (closing || _state.value is ConnectionState.Disconnected) return
        Log.w(TAG, "link lost: $reason")
        closeSocket()
        _state.value = ConnectionState.Failed(reason)
    }

    // ------------------------------------------------------------ operations

    private fun requireReady() {
        if (!_state.value.isReady) throw TransportException("not connected")
    }

    /** `HELLO` carries the `info` document; there is no separate read over LAN. */
    override suspend fun readInfo(): ByteArray {
        requireReady()
        return (hello ?: throw TransportException("no HELLO")).infoJson.encodeToByteArray()
    }

    /** Latest `STATUS` push, or ask for one with `0x07` and wait. */
    override suspend fun readStatus(): ByteArray {
        requireReady()
        _status.replayCache.firstOrNull()?.let { return it }
        writeControl(byteArrayOf(Opcode.STATUS.toByte()))
        return withTimeoutOrNull(PUSH_WAIT_MS) { _status.first() }
            ?: throw TransportException("no STATUS push within ${PUSH_WAIT_MS / 1000} s")
    }

    /** Latest `LIVE` push, or wait for the next sample (every 10 s). Nothing can request one. */
    override suspend fun readLive(): ByteArray {
        requireReady()
        _live.replayCache.firstOrNull()?.let { return it }
        return withTimeoutOrNull(LIVE_WAIT_MS) { _live.first() }
            ?: throw TransportException("no LIVE push within ${LIVE_WAIT_MS / 1000} s")
    }

    override suspend fun writeControl(request: ByteArray) {
        require(request.size in 1..LanFraming.REQUEST_MAX) { "control request is ${request.size} bytes; must be 1..${LanFraming.REQUEST_MAX}" }
        requireReady()
        val out = output ?: throw TransportException("not connected")
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    LanFraming.write(out, request)
                    lastTxAt.set(System.currentTimeMillis())
                } catch (e: IOException) {
                    linkLost("write failed: ${e.message}")
                    throw TransportException("LAN write failed: ${e.message}", cause = e)
                }
            }
        }
    }

    // ------------------------------------------------------------- lifecycle

    override suspend fun disconnect() {
        closeSocket()
        _state.value = ConnectionState.Disconnected
    }

    private fun closeSocket() {
        closing = true
        readerJob?.cancel()
        readerJob = null
        keepaliveJob?.cancel()
        keepaliveJob = null
        socket?.let { runCatching { it.close() } }
        socket = null
        input = null
        output = null
    }

    override fun close() {
        closeSocket()
        scope.cancel()
        _responses.close()
        _state.value = ConnectionState.Disconnected
    }

    companion object {
        private const val TAG = "TcpTransport"
        private const val RESPONSE_CAPACITY = 64
        const val CONNECT_TIMEOUT_MS = 5_000
        const val HANDSHAKE_TIMEOUT_MS = 8_000
        const val KEEPALIVE_POLL_MS = 15_000L
        const val DEAD_AFTER_MS = 180_000L
        const val PUSH_WAIT_MS = 10_000L
        const val LIVE_WAIT_MS = 15_000L

        /** A `HELLO` advertising less than this cannot carry an `OPENED` with a real name. */
        const val MIN_PAYLOAD_MAX = 64
    }
}
