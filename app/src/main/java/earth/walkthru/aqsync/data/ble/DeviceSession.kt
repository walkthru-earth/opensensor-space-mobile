package earth.walkthru.aqsync.data.ble

import earth.walkthru.aqsync.data.protocol.DeviceInfo
import earth.walkthru.aqsync.data.protocol.DeviceStatus
import earth.walkthru.aqsync.data.protocol.Frame
import earth.walkthru.aqsync.data.protocol.FrameCodec
import earth.walkthru.aqsync.data.protocol.FrameDecodeException
import earth.walkthru.aqsync.data.protocol.LiveSample
import earth.walkthru.aqsync.data.protocol.Opcode
import earth.walkthru.aqsync.data.protocol.ProtocolError
import earth.walkthru.aqsync.data.protocol.Request
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** The device answered a request with an `ERROR` frame. */
class DeviceErrorException(val frame: Frame.Error) : Exception(frame.describe()) {
    val protocolError: ProtocolError? get() = frame.error
}

/** The device sent a frame that does not belong to the request in flight. */
class ProtocolViolationException(message: String) : Exception(message)

/**
 * One request at a time over a single `response` characteristic.
 *
 * The protocol has no request ids: "All `response` traffic for one request is emitted in order on
 * the single `response` characteristic". We therefore serialize requests with a [Mutex] and
 * consume frames from the transport's ordered channel until the request's terminal frame arrives.
 */
class DeviceSession(val transport: AqTransport) {
    private val requestMutex = Mutex()

    val address: String get() = transport.address
    val connectionState get() = transport.connectionState

    /** `payload_max = MTU − 3`. */
    val payloadMax: Int get() = transport.mtu - 3

    val status: Flow<DeviceStatus> = transport.statusNotifications.mapNotNull { raw ->
        runCatching { DeviceStatus.parse(raw.decodeToString()) }.getOrNull()
    }

    val live: Flow<LiveSample> = transport.liveNotifications.mapNotNull { raw ->
        runCatching { LiveSample.parse(raw.decodeToString()) }.getOrNull()
    }

    suspend fun readInfo(): DeviceInfo = DeviceInfo.parse(transport.readInfo().decodeToString())
    suspend fun readStatus(): DeviceStatus = DeviceStatus.parse(transport.readStatus().decodeToString())
    suspend fun readLive(): LiveSample = LiveSample.parse(transport.readLive().decodeToString())

    /** `LIST` → `FILE` × n, then `LIST_END`. */
    suspend fun list(): Pair<List<Frame.File>, Frame.ListEnd> = requestMutex.withLock {
        drainStale()
        transport.writeControl(Request.List.encode())
        val files = ArrayList<Frame.File>()
        while (true) {
            when (val frame = nextFrame(Opcode.LIST, FRAME_TIMEOUT_MS)) {
                is Frame.File -> files += frame
                is Frame.ListEnd -> return files to frame
                else -> throw unexpected("LIST", frame)
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    /**
     * `OPEN name` → `OPENED` or `ERROR`. The device computes the CRC-32 over the whole file before
     * answering, so the timeout is generous.
     */
    suspend fun open(name: String): Frame.Opened = requestMutex.withLock {
        drainStale()
        transport.writeControl(Request.Open(name).encode())
        when (val frame = nextFrame(Opcode.OPEN, OPEN_TIMEOUT_MS)) {
            is Frame.Opened -> frame
            else -> throw unexpected("OPEN", frame)
        }
    }

    /**
     * `READ handle offset length` → `CHUNK` × n, then `READ_END`. Chunks are handed to [onChunk]
     * as they arrive so a large file never has to sit in memory twice.
     */
    suspend fun read(
        handle: Int,
        offset: Long,
        length: Long,
        onChunk: suspend (Frame.Chunk) -> Unit,
    ): Frame.ReadEnd = requestMutex.withLock {
        drainStale()
        transport.writeControl(Request.Read(handle, offset, length).encode())
        while (true) {
            when (val frame = nextFrame(Opcode.READ, FRAME_TIMEOUT_MS)) {
                is Frame.Chunk -> {
                    if (frame.handle != handle) {
                        throw ProtocolViolationException("CHUNK for handle ${frame.handle}, expected $handle")
                    }
                    onChunk(frame)
                }
                is Frame.ReadEnd -> return frame
                else -> throw unexpected("READ", frame)
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    /** `CLOSE handle` → `CLOSED`. */
    suspend fun close(handle: Int): Frame.Closed = requestMutex.withLock {
        drainStale()
        transport.writeControl(Request.Close(handle).encode())
        when (val frame = nextFrame(Opcode.CLOSE, FRAME_TIMEOUT_MS)) {
            is Frame.Closed -> frame
            else -> throw unexpected("CLOSE", frame)
        }
    }

    /** `SET_TIME epoch_s` → `TIME_SET` or `ERROR` 9. Only rows sampled afterwards get UTC. */
    suspend fun setTime(epochSeconds: Long): Frame.TimeSet = requestMutex.withLock {
        drainStale()
        transport.writeControl(Request.SetTime(epochSeconds).encode())
        when (val frame = nextFrame(Opcode.SET_TIME, FRAME_TIMEOUT_MS)) {
            is Frame.TimeSet -> frame
            else -> throw unexpected("SET_TIME", frame)
        }
    }

    /** `FLUSH` → `FLUSHED` or `ERROR` 10. Finalizes the RAM batch into a short file. */
    suspend fun flush(): Frame.Flushed = requestMutex.withLock {
        drainStale()
        transport.writeControl(Request.Flush.encode())
        when (val frame = nextFrame(Opcode.FLUSH, OPEN_TIMEOUT_MS)) {
            is Frame.Flushed -> frame
            else -> throw unexpected("FLUSH", frame)
        }
    }

    /** `STATUS` → a `status` notification only; no `response` frame is defined. */
    suspend fun requestStatus() = requestMutex.withLock {
        transport.writeControl(Request.Status.encode())
    }

    /**
     * Wait for the next frame of the request in flight. An `ERROR` frame whose `op` matches is the
     * request's own failure; an `ERROR` for another op is a protocol violation.
     */
    private suspend fun nextFrame(op: Int, timeoutMs: Long): Frame {
        val raw = receiveOrFail(timeoutMs)
        val frame = try {
            FrameCodec.decode(raw)
        } catch (e: FrameDecodeException) {
            throw ProtocolViolationException("undecodable response frame during ${Opcode.name(op)}: ${e.message}")
        }
        if (frame is Frame.Error) {
            if (frame.op == op || frame.op == 0) throw DeviceErrorException(frame)
            throw ProtocolViolationException("ERROR for ${Opcode.name(frame.op)} while waiting on ${Opcode.name(op)}: ${frame.describe()}")
        }
        return frame
    }

    /**
     * Receive the next raw frame, but give up early if the link drops: a lost connection would
     * otherwise only surface as a slow timeout.
     */
    private suspend fun receiveOrFail(timeoutMs: Long): ByteArray {
        var waited = 0L
        while (waited < timeoutMs) {
            val slice = minOf(POLL_MS, timeoutMs - waited)
            withTimeoutOrNull(slice) { transport.responseFrames.receiveCatching().getOrNull() }
                ?.let { return it }
            waited += slice
            val st = transport.connectionState.value
            if (st is ConnectionState.Disconnected || st is ConnectionState.Failed) {
                throw TransportException("link lost while waiting for a response")
            }
            if (transport.responseFrames.isClosedForReceive) {
                throw TransportException("transport closed")
            }
        }
        throw TransportException("no response from device within ${timeoutMs / 1000} s")
    }

    /**
     * Frames left over from an aborted earlier request (e.g. a READ cancelled mid-stream) would
     * otherwise be mistaken for the new request's response. Drop whatever is already queued.
     */
    private fun drainStale() {
        var dropped = 0
        while (transport.responseFrames.tryReceive().isSuccess) dropped++
        if (dropped > 0) staleFrameLogger("dropped $dropped stale response frames")
    }

    /** Overridable so unit tests stay free of android.util.Log. */
    var staleFrameLogger: (String) -> Unit = {}

    private fun unexpected(request: String, frame: Frame) =
        ProtocolViolationException("unexpected ${frame::class.simpleName} frame in response to $request")

    companion object {
        /** Gap between two frames of one response, including LIST on a large card. */
        const val FRAME_TIMEOUT_MS = 15_000L

        /** OPEN reads the whole file for CRC-32 on the device; FLUSH writes a file. */
        const val OPEN_TIMEOUT_MS = 60_000L

        private const val POLL_MS = 250L
    }
}

/** Convenience for UI code that wants the raw JSON as text. */
fun Flow<ByteArray>.asUtf8(): Flow<String> = map { it.decodeToString() }
