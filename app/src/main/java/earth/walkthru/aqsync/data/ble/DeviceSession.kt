package earth.walkthru.aqsync.data.ble

import earth.walkthru.aqsync.data.protocol.ConfigPatch
import earth.walkthru.aqsync.data.protocol.DeviceConfig
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

/** The device answered a request with an `ERROR` frame. Subclasses type the v2 codes callers branch on. */
open class DeviceErrorException(val frame: Frame.Error) : Exception(frame.describe()) {
    val protocolError: ProtocolError? get() = frame.error

    companion object {
        /** Pick the typed subclass for an `ERROR` frame. */
        fun of(frame: Frame.Error): DeviceErrorException = when (frame.code) {
            ProtocolError.INVALID_CONFIG.code -> InvalidConfigException(frame)
            ProtocolError.NOT_ALLOWED_ON_LINK.code -> NotAllowedOnLinkException(frame)
            ProtocolError.AUTH_FAILED.code -> AuthFailedException(frame)
            ProtocolError.WIFI_UNAVAILABLE.code -> WifiUnavailableException(frame)
            ProtocolError.BUSY.code -> DeviceBusyException(frame)
            else -> DeviceErrorException(frame)
        }
    }
}

/** `ERROR` 12: one key was rejected and nothing changed; [key] names it. */
class InvalidConfigException(frame: Frame.Error) : DeviceErrorException(frame) {
    val key: String get() = frame.detail
}

/** `ERROR` 13: e.g. `GET_TOKEN` over LAN. */
class NotAllowedOnLinkException(frame: Frame.Error) : DeviceErrorException(frame)

/** `ERROR` 14: LAN handshake token rejected. */
class AuthFailedException(frame: Frame.Error) : DeviceErrorException(frame)

/** `ERROR` 15: the radio cannot scan right now. */
class WifiUnavailableException(frame: Frame.Error) : DeviceErrorException(frame)

/** `ERROR` 7: command queue full, transfer in progress, or a second LAN client. */
class DeviceBusyException(frame: Frame.Error) : DeviceErrorException(frame)

/** `GET_CONFIG` / `SET_CONFIG` answer: the parsed document plus the raw JSON for the debug view. */
data class ConfigSnapshot(val config: DeviceConfig, val rebootRequired: Boolean, val rawJson: String, val atEpochMs: Long)

/** `WIFI_SCAN` answer. */
data class WifiScanResult(val accessPoints: List<Frame.WifiAp>, val end: Frame.WifiScanEnd)

/**
 * `LOG_TAIL` answer. [complete] is false when the concatenated slices do not add up to
 * `LOG_END.returned`; the text is still shown (it is for humans), flagged as possibly missing a slice.
 */
data class LogTail(val text: String, val totalBytesSinceBoot: Long, val returned: Int, val receivedBytes: Int) {
    val complete: Boolean get() = returned == receivedBytes
}

/** The device sent a frame that does not belong to the request in flight, or one that cannot be decoded. */
class ProtocolViolationException(message: String) : Exception(message)

/**
 * A `READ` window arrived incomplete: a `CHUNK` skipped ahead of the expected offset, or
 * `READ_END.next_offset` did not match the bytes received. The window's `READ_END` *has* been
 * consumed, so the caller may immediately re-issue `READ` from [receivedUpTo] — offsets are absolute
 * and file bytes are immutable, so the retry is idempotent. Seen 2026-09-17 on a OnePlus 7 Pro
 * (Android 16): 16–18 consecutive `CHUNK`s missing at the start of every window. Root cause was a
 * firmware frame of 514 bytes exceeding the 512-byte GATT attribute limit (Android discards such
 * notifications silently); fixed there, but a lossy link must still end in a re-read, not an abort.
 */
class WindowLostException(val receivedUpTo: Long, reason: String) : Exception(reason)

/**
 * One request at a time over a single `response` characteristic.
 *
 * The protocol has no request ids: "All `response` traffic for one request is emitted in order on
 * the single `response` characteristic". We therefore serialize requests with a [Mutex] and
 * consume frames from the transport's ordered channel until the request's terminal frame arrives.
 */
class DeviceSession(val transport: AqTransport) {
    private val requestMutex = Mutex()

    /** Raw frames in both directions, for Advanced → Debug. Secrets are redacted at record time. */
    val frames = FrameRecorder()

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

    /** The raw `status` JSON text as it arrives, for the debug view (pretty-printed there). */
    val statusJson: Flow<String> = transport.statusNotifications.map { it.decodeToString() }

    suspend fun readInfo(): DeviceInfo = DeviceInfo.parse(transport.readInfo().decodeToString())
    suspend fun readStatus(): DeviceStatus = DeviceStatus.parse(transport.readStatus().decodeToString())
    suspend fun readLive(): LiveSample = LiveSample.parse(transport.readLive().decodeToString())

    private suspend fun write(request: Request) {
        val bytes = request.encode()
        frames.tx(bytes)
        transport.writeControl(bytes)
    }

    /** `LIST` → `FILE` × n, then `LIST_END`. */
    suspend fun list(): Pair<List<Frame.File>, Frame.ListEnd> = request(Opcode.LIST) {
        write(Request.List)
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
    suspend fun open(name: String): Frame.Opened = request(Opcode.OPEN) {
        write(Request.Open(name))
        when (val frame = nextFrame(Opcode.OPEN, OPEN_TIMEOUT_MS)) {
            is Frame.Opened -> frame
            else -> throw unexpected("OPEN", frame)
        }
    }

    /**
     * `READ handle offset length` → `CHUNK` × n, then `READ_END`. Chunks are handed to [onChunk]
     * as they arrive so a large file never has to sit in memory twice.
     *
     * Continuity is checked here, against the absolute offsets the contract puts in every `CHUNK`.
     * A chunk that skips ahead, or a `READ_END.next_offset` that does not equal the bytes received,
     * means notifications were lost on the way to the phone. The window is then *not* abandoned
     * mid-stream: the remaining frames of this request are consumed (and discarded) until its
     * `READ_END`, so the channel is clean, and only then is [WindowLostException] thrown. The caller
     * re-issues `READ` from [WindowLostException.receivedUpTo]. A `CHUNK` for another handle or an
     * undecodable frame is still a [ProtocolViolationException]: that stream is malformed, not lossy.
     */
    suspend fun read(
        handle: Int,
        offset: Long,
        length: Long,
        onChunk: suspend (Frame.Chunk) -> Unit,
    ): Frame.ReadEnd = request(Opcode.READ) {
        write(Request.Read(handle, offset, length))
        var expected = offset
        var lost: String? = null
        var discarded = 0
        while (true) {
            when (val frame = nextFrame(Opcode.READ, FRAME_TIMEOUT_MS)) {
                is Frame.Chunk -> {
                    if (frame.handle != handle) {
                        throw ProtocolViolationException("CHUNK for handle ${frame.handle}, expected $handle")
                    }
                    when {
                        lost != null -> discarded++
                        frame.offset > expected -> {
                            lost = "gap in stream: expected offset $expected, got CHUNK at ${frame.offset}"
                            discarded++
                        }
                        else -> {
                            // In order, or a duplicate/overlap (identical bytes: the file is immutable).
                            onChunk(frame)
                            expected = maxOf(expected, frame.offset + frame.payload.size)
                        }
                    }
                }
                is Frame.ReadEnd -> {
                    lost?.let {
                        throw WindowLostException(expected, "$it; $discarded later CHUNK(s) of the window discarded")
                    }
                    if (frame.ok && frame.nextOffset != expected) {
                        throw WindowLostException(
                            expected,
                            "READ_END next_offset=${frame.nextOffset} but $expected is where the received bytes end"
                        )
                    }
                    return frame
                }
                else -> throw unexpected("READ", frame)
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    /** `CLOSE handle` → `CLOSED`. */
    suspend fun close(handle: Int): Frame.Closed = request(Opcode.CLOSE) {
        write(Request.Close(handle))
        when (val frame = nextFrame(Opcode.CLOSE, FRAME_TIMEOUT_MS)) {
            is Frame.Closed -> frame
            else -> throw unexpected("CLOSE", frame)
        }
    }

    /** `SET_TIME epoch_s` → `TIME_SET` or `ERROR` 9. Only rows sampled afterwards get UTC. */
    suspend fun setTime(epochSeconds: Long): Frame.TimeSet = request(Opcode.SET_TIME) {
        write(Request.SetTime(epochSeconds))
        when (val frame = nextFrame(Opcode.SET_TIME, FRAME_TIMEOUT_MS)) {
            is Frame.TimeSet -> frame
            else -> throw unexpected("SET_TIME", frame)
        }
    }

    /** `FLUSH` → `FLUSHED` or `ERROR` 10. Finalizes the RAM batch into a short file. */
    suspend fun flush(): Frame.Flushed = request(Opcode.FLUSH) {
        write(Request.Flush)
        when (val frame = nextFrame(Opcode.FLUSH, OPEN_TIMEOUT_MS)) {
            is Frame.Flushed -> frame
            else -> throw unexpected("FLUSH", frame)
        }
    }

    /** `STATUS` → a `status` notification only; no `response` frame is defined. */
    suspend fun requestStatus() = requestMutex.withLock {
        write(Request.Status)
    }

    // ------------------------------------------------------------------------ v2

    /** `GET_CONFIG` → `CONFIG`. */
    suspend fun getConfig(): ConfigSnapshot = request(Opcode.GET_CONFIG) {
        write(Request.GetConfig)
        configFrom("GET_CONFIG", nextFrame(Opcode.GET_CONFIG, FRAME_TIMEOUT_MS))
    }

    /**
     * `SET_CONFIG key=value…` → `CONFIG` (the new state) or `ERROR` 12 ([InvalidConfigException]
     * with the offending key; nothing changed). Values are validated by [ConfigPatch] before send.
     */
    suspend fun setConfig(patch: ConfigPatch): ConfigSnapshot = request(Opcode.SET_CONFIG) {
        require(!patch.isEmpty) { "empty config patch" }
        write(Request.SetConfig(patch))
        configFrom("SET_CONFIG", nextFrame(Opcode.SET_CONFIG, FRAME_TIMEOUT_MS))
    }

    private fun configFrom(request: String, frame: Frame): ConfigSnapshot {
        if (frame !is Frame.Config) throw unexpected(request, frame)
        val parsed = try {
            DeviceConfig.parse(frame.json)
        } catch (e: Exception) {
            throw ProtocolViolationException("CONFIG JSON did not parse: ${e.message}")
        }
        return ConfigSnapshot(parsed, frame.rebootRequired, frame.json, System.currentTimeMillis())
    }

    /**
     * `WIFI_SCAN` → `WIFI_AP` × n, then `WIFI_SCAN_END`. Takes 2–4 s on the device. `ERROR` 15 (or a
     * non-zero end status) surfaces as [WifiUnavailableException].
     */
    suspend fun wifiScan(): WifiScanResult = request(Opcode.WIFI_SCAN) {
        write(Request.WifiScan)
        val aps = ArrayList<Frame.WifiAp>()
        while (true) {
            when (val frame = nextFrame(Opcode.WIFI_SCAN, WIFI_SCAN_TIMEOUT_MS)) {
                is Frame.WifiAp -> aps += frame
                is Frame.WifiScanEnd -> {
                    if (!frame.ok) {
                        throw DeviceErrorException.of(Frame.Error(Opcode.WIFI_SCAN, frame.status, "WIFI_SCAN_END status"))
                    }
                    return WifiScanResult(aps, frame)
                }
                else -> throw unexpected("WIFI_SCAN", frame)
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    /**
     * `REBOOT` → `REBOOTING delay_ms`; the device finalizes its RAM batch and restarts. The caller
     * should disconnect within `delayMs` and reconnect afterwards.
     */
    suspend fun reboot(): Frame.Rebooting = request(Opcode.REBOOT) {
        write(Request.Reboot)
        when (val frame = nextFrame(Opcode.REBOOT, OPEN_TIMEOUT_MS)) {
            is Frame.Rebooting -> frame
            else -> throw unexpected("REBOOT", frame)
        }
    }

    /** `LOG_TAIL max_bytes` → `LOG` × n, then `LOG_END`. Text for humans, not an API. */
    suspend fun logTail(maxBytes: Int = LOG_RING_BYTES): LogTail = request(Opcode.LOG_TAIL) {
        write(Request.LogTail(maxBytes.coerceIn(0, 0xFFFF)))
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            when (val frame = nextFrame(Opcode.LOG_TAIL, FRAME_TIMEOUT_MS)) {
                is Frame.Log -> out.write(frame.bytes)
                is Frame.LogEnd -> {
                    val bytes = out.toByteArray()
                    return LogTail(bytes.decodeToString(), frame.total, frame.returned, bytes.size)
                }
                else -> throw unexpected("LOG_TAIL", frame)
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    /**
     * `GET_TOKEN` → `TOKEN port, token`. BLE only: over LAN the device answers `ERROR` 13
     * ([NotAllowedOnLinkException]). The token is a bearer secret — store it, never log it.
     */
    suspend fun getToken(): Frame.Token = request(Opcode.GET_TOKEN) {
        write(Request.GetToken)
        when (val frame = nextFrame(Opcode.GET_TOKEN, FRAME_TIMEOUT_MS)) {
            is Frame.Token -> frame
            else -> throw unexpected("GET_TOKEN", frame)
        }
    }

    /**
     * Advanced → Debug "raw command": write arbitrary bytes and collect whatever comes back until
     * the device has been quiet for [quietMs] (there is no generic terminal frame). Undecodable
     * frames are returned as [Frame.Error] placeholders with code −1 rather than thrown, so the user
     * sees exactly what arrived.
     */
    suspend fun rawCommand(request: ByteArray, quietMs: Long = 1_500L, maxTotalMs: Long = FRAME_TIMEOUT_MS): List<Pair<ByteArray, Frame?>> =
        request(NO_TERMINAL) {
            require(request.isNotEmpty() && request.size <= 512) { "request must be 1..512 bytes" }
            frames.tx(request)
            transport.writeControl(request)
            val got = ArrayList<Pair<ByteArray, Frame?>>()
            val started = System.currentTimeMillis()
            while (System.currentTimeMillis() - started < maxTotalMs) {
                val raw = withTimeoutOrNull(quietMs) { transport.responseFrames.receiveCatching().getOrNull() } ?: break
                frames.rx(raw)
                got += raw to runCatching { FrameCodec.decode(raw) }.getOrNull()
            }
            got
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
            if (frame.op == op || frame.op == 0) throw DeviceErrorException.of(frame)
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
                ?.let { frames.rx(it); return it }
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
     * Serialize one request and remember whether it left the channel dirty.
     *
     * Any failure or cancellation *before* the request's terminal frame arrived (timeout, link
     * hiccup, a stray frame, a cancelled READ) leaves the rest of the reply in flight; the next
     * request must not mistake it for its own. [drainStale] then waits for exactly that terminal
     * frame. A [DeviceErrorException] (the `ERROR` *is* the terminal frame) and a
     * [WindowLostException] (thrown only after `READ_END` was consumed) leave the channel clean.
     */
    private suspend inline fun <T> request(op: Int, block: () -> T): T = requestMutex.withLock {
        drainStale()
        try {
            block()
        } catch (e: WindowLostException) {
            throw e
        } catch (e: DeviceErrorException) {
            throw e
        } catch (e: Throwable) {
            pendingDrain = op
            throw e
        }
    }

    /** Opcode of the last request that failed before its terminal frame; [NO_TERMINAL] for a raw command. */
    private var pendingDrain: Int? = null

    /**
     * Frames left over from an aborted earlier request (e.g. a READ cancelled mid-stream, or a
     * `CLOSED` that arrived after its 3 s budget) would otherwise be mistaken for the new request's
     * response — "unexpected Closed frame in response to LIST" was seen on a real phone. Drop
     * whatever is already queued and, if the previous request failed before its terminal frame,
     * keep consuming until that frame shows up, the device has been quiet for [DRAIN_QUIET_MS], or
     * [FRAME_TIMEOUT_MS] has elapsed.
     */
    private suspend fun drainStale() {
        val pending = pendingDrain
        pendingDrain = null
        var dropped = 0
        var terminalSeen = false
        val deadline = System.currentTimeMillis() + FRAME_TIMEOUT_MS
        while (true) {
            val raw = if (pending == null) {
                transport.responseFrames.tryReceive().getOrNull() ?: break
            } else {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) break
                withTimeoutOrNull(minOf(DRAIN_QUIET_MS, remaining)) { transport.responseFrames.receiveCatching().getOrNull() } ?: break
            }
            dropped++
            if (pending != null && isTerminal(pending, raw)) {
                terminalSeen = true
                break
            }
        }
        if (dropped > 0 || pending != null) {
            staleFrameLogger(
                "dropped $dropped stale response frame(s)" +
                    (pending?.let { " after failed ${Opcode.name(it)}" + if (terminalSeen) " (terminal frame seen)" else " (quiet)" } ?: "")
            )
        }
    }

    /** Whether [raw] ends the reply to [op]: its terminal frame, or an `ERROR` addressed to it. */
    private fun isTerminal(op: Int, raw: ByteArray): Boolean {
        if (op == NO_TERMINAL) return false
        val frame = runCatching { FrameCodec.decode(raw) }.getOrNull() ?: return false
        if (frame is Frame.Error) return frame.op == op || frame.op == 0
        return when (op) {
            Opcode.LIST -> frame is Frame.ListEnd
            Opcode.OPEN -> frame is Frame.Opened
            Opcode.READ -> frame is Frame.ReadEnd
            Opcode.CLOSE -> frame is Frame.Closed
            Opcode.SET_TIME -> frame is Frame.TimeSet
            Opcode.FLUSH -> frame is Frame.Flushed
            Opcode.GET_CONFIG, Opcode.SET_CONFIG -> frame is Frame.Config
            Opcode.WIFI_SCAN -> frame is Frame.WifiScanEnd
            Opcode.REBOOT -> frame is Frame.Rebooting
            Opcode.LOG_TAIL -> frame is Frame.LogEnd
            Opcode.GET_TOKEN -> frame is Frame.Token
            else -> false
        }
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

        /** WIFI_SCAN takes 2–4 s on the Wi-Fi task; allow for a busy radio. */
        const val WIFI_SCAN_TIMEOUT_MS = 20_000L

        /** The device's serial log ring. */
        const val LOG_RING_BYTES = 8192

        /**
         * After a failed request, how long the device may stay silent before the channel is
         * considered clean when its terminal frame never showed up.
         */
        const val DRAIN_QUIET_MS = 2_000L

        private const val POLL_MS = 250L

        /** Pseudo-opcode for requests without a terminal frame (raw commands): drain on quiet only. */
        private const val NO_TERMINAL = -1
    }
}

/** Convenience for UI code that wants the raw JSON as text. */
fun Flow<ByteArray>.asUtf8(): Flow<String> = map { it.decodeToString() }
