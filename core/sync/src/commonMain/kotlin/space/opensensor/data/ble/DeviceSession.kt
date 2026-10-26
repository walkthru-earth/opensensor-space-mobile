package space.opensensor.data.ble

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeoutOrNull
import space.opensensor.data.protocol.ConfigChunkAccumulator
import space.opensensor.data.protocol.ConfigPatch
import space.opensensor.data.protocol.DeviceConfig
import space.opensensor.data.protocol.DeviceInfo
import space.opensensor.data.protocol.DeviceStatus
import space.opensensor.data.protocol.Frame
import space.opensensor.data.protocol.LiveSample
import space.opensensor.data.protocol.Opcode
import space.opensensor.data.protocol.ProtocolError
import space.opensensor.data.protocol.Request
import space.opensensor.data.sync.AdaptiveReadWindow
import space.opensensor.data.sync.ClockSyncAction
import space.opensensor.data.sync.SmartSyncPolicy
import space.opensensor.link.LinkLog
import space.opensensor.link.LinkSession
import space.opensensor.link.LinkState
import space.opensensor.link.LinkTimeoutException
import space.opensensor.link.RequestSpec
import space.opensensor.link.TransportLinkDriver
import space.opensensor.sync.nowEpochMs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

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
            ProtocolError.NO_DESCRIPTOR.code -> NoDescriptorException(frame)
            else -> DeviceErrorException(frame)
        }
    }
}

/**
 * A `SET_CONFIG` carrying `wifi.psk` or `ble.pin` was refused before it was written, because the
 * session runs on a pipe that may not carry secrets (plaintext LAN). Nothing was sent. The caller
 * offers the Bluetooth reconnect instead (#155).
 */
class SecretNeedsBluetoothException(val kind: TransportKind) : IllegalStateException("a secret config patch is sent over Bluetooth only, this link is ${kind.name}")

/** `ERROR` 17: the firmware carries no board template to serve. */
class NoDescriptorException(frame: Frame.Error) : DeviceErrorException(frame)

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
data class ConfigSnapshot(val config: DeviceConfig, val rebootRequired: Boolean, val rawJson: String, val atEpochMs: Long) {
    /** Leaves out [rawJson], which can hold the passkey. [config] redacts its own secrets. */
    override fun toString(): String =
        "ConfigSnapshot(config=$config, rebootRequired=$rebootRequired, rawJson=<${rawJson.length} chars>, atEpochMs=$atEpochMs)"
}

/** `WIFI_SCAN` answer. */
data class WifiScanResult(val accessPoints: List<Frame.WifiAp>, val end: Frame.WifiScanEnd)

/**
 * `LOG_TAIL` answer. [complete] is false when the concatenated slices do not add up to
 * `LOG_END.returned`; the text is still shown (it is for humans), flagged as possibly missing a slice.
 */
data class LogTail(val text: String, val totalBytesSinceBoot: Long, val returned: Int, val receivedBytes: Int) {
    val complete: Boolean get() = returned == receivedBytes
}

/** A debug raw command named an opcode in [DeviceSession.RAW_COMMAND_DENYLIST]; nothing was sent. */
class RawCommandRefusedException(val op: Int) : IllegalArgumentException("${Opcode.name(op)} is not sent from the raw command; use the Device screen, which asks for confirmation")

/** The device sent a reply that breaks the contract, for example a LIST whose count does not match. */
class ProtocolViolationException(message: String) : Exception(message)

/**
 * A `READ` window arrived incomplete: a `CHUNK` skipped ahead of the expected offset, or
 * `READ_END.next_offset` did not match the bytes received. The window's `READ_END` has been
 * consumed, so the caller may immediately re-issue `READ` from [receivedUpTo] — offsets are absolute
 * and file bytes are immutable, so the retry is idempotent. Seen 2026-09-17 on a OnePlus 7 Pro
 * (Android 16): 16–18 consecutive `CHUNK`s missing at the start of every window. Root cause was a
 * firmware frame of 514 bytes exceeding the 512-byte GATT attribute limit (Android discards such
 * notifications silently); fixed there, but a lossy link must still end in a re-read, not an abort.
 */
class WindowLostException(val receivedUpTo: Long, reason: String) : Exception(reason)

/**
 * The typed device API, a thin view over one [LinkSession]. The session owns the reply routing,
 * the drain of abandoned replies and any owed `CLOSE`, so this class holds no protocol state and
 * any number of views on one session share it. Making a view per run is harmless.
 *
 * [transport] still supplies identity, connection state, the GATT reads and the push flows until
 * the Android and iOS drivers land (#57, #58). It is never read for replies. [frames] records raw
 * frames for Advanced, Debug, filled by the driver ([TransportLinkDriver]) with secrets redacted.
 */
class DeviceSession(val link: LinkSession, val transport: DeviceTransport, val frames: FrameRecorder = FrameRecorder()) {
    val address: String get() = transport.address
    val connectionState get() = transport.connectionState

    /** `payload_max = MTU − 3`. */
    val payloadMax: Int get() = transport.mtu - 3

    private var window: AdaptiveReadWindow? = null

    /**
     * The adaptive `READ` window of this link (#98), made on first use from the device's [maxRead],
     * the link kind and [payloadMax], and kept for every later file so a weak link stays small.
     */
    fun readWindow(maxRead: Int?): AdaptiveReadWindow {
        val fresh = AdaptiveReadWindow.of(transport.kind, payloadMax, maxRead)
        val held = window
        if (held != null && held.max == fresh.max && held.frame == fresh.frame) return held
        return fresh.also { window = it }
    }

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

    /**
     * `LIST` → `FILE` × n, then `LIST_END`. An incomplete listing is never a successful snapshot.
     * The count is checked after `LIST_END` was consumed, so a wrong count leaves the link ready.
     */
    suspend fun list(): Pair<List<Frame.File>, Frame.ListEnd> {
        val files = ArrayList<Frame.File>()
        val end = request(Request.List) { frame ->
            when (frame) {
                is Frame.File -> {
                    files += frame
                    null
                }
                is Frame.ListEnd -> frame
                else -> throw unexpected("LIST", frame)
            }
        }
        if (files.size.toLong() != end.count) throw ProtocolViolationException("LIST count ${end.count} differs from ${files.size} FILE frames")
        return files to end
    }

    /**
     * `OPEN name` → `OPENED` or `ERROR`. The device computes the CRC-32 over the whole file before
     * answering, so the first frame may take up to [RequestSpec.SLOW_FIRST_FRAME].
     */
    suspend fun open(name: String): Frame.Opened = single(Request.Open(name), "OPEN")

    /**
     * `READ handle offset length` → `CHUNK` × n, then `READ_END`. Chunks are handed to [onChunk]
     * as they arrive, so a large file never has to sit in memory twice. A suspending [onChunk]
     * holds the reply, so the caller's next `READ` waits until it accepted this window.
     *
     * Continuity is checked against the absolute offsets the contract puts in every `CHUNK`. A
     * chunk that skips ahead, or a `READ_END.next_offset` that does not equal the bytes received,
     * means notifications were lost on the way to the phone. The rest of the window is consumed
     * and discarded until its `READ_END`, and only then is [WindowLostException] thrown. The caller
     * re-issues `READ` from [WindowLostException.receivedUpTo]. A `CHUNK` for another handle is a
     * [ProtocolViolationException]: that stream is malformed, not lossy.
     */
    suspend fun read(
        handle: Int,
        offset: Long,
        length: Long,
        onChunk: suspend (Frame.Chunk) -> Unit,
    ): Frame.ReadEnd {
        var expected = offset
        var lost: String? = null
        var discarded = 0
        val end = request(Request.Read(handle, offset, length)) { frame ->
            when (frame) {
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
                    null
                }
                is Frame.ReadEnd -> frame
                else -> throw unexpected("READ", frame)
            }
        }
        lost?.let { throw WindowLostException(expected, "$it; $discarded later CHUNK(s) of the window discarded") }
        if (end.ok && end.nextOffset != expected) {
            throw WindowLostException(expected, "READ_END next_offset=${end.nextOffset} but $expected is where the received bytes end")
        }
        return end
    }

    /** `CLOSE handle` → `CLOSED`. */
    suspend fun close(handle: Int): Frame.Closed = single(Request.Close(handle), "CLOSE", oweCloseIfUnsent = handle)

    /**
     * Leave `CLOSE handle` to the link without waiting. Used when a transfer is cancelled or
     * failed, so Stop never waits behind the abandoned `READ` ([LinkSession.oweClose]).
     */
    fun oweClose(handle: Int) = link.oweClose(handle)

    /** `SET_TIME` → `TIME_SET` or `ERROR` 9. The acknowledged anchor also reconciles earlier boot rows. */
    suspend fun setTime(epochSeconds: Long, subsecondMicros: Int? = null): Frame.TimeSet =
        single(Request.SetTime(epochSeconds, subsecondMicros), "SET_TIME")

    /** Automatic UTC anchor. Retry legacy seconds only when old firmware rejects the extension. */
    suspend fun synchronizeClock(nowMillis: () -> Long = ::nowEpochMs): Frame.TimeSet {
        val millis = nowMillis()
        return try {
            setTime(millis / 1000, ((millis % 1000) * 1000).toInt())
        } catch (e: DeviceErrorException) {
            if (e.protocolError != ProtocolError.MALFORMED_REQUEST) throw e
            setTime(nowMillis() / 1000)
        }
    }

    /**
     * Shared Android/iOS clock maintenance. Request fresh STATUS before deciding, rather than
     * treating reconnect or a new native byte pipe as a reason to cut the current archive window.
     * Null means the external clock is already usable; only a real TIME_SET is receipt evidence.
     * Legacy status has no age/drift fields, so a reported usable UTC anchor is retained.
     */
    suspend fun synchronizeClockIfNeeded(nowMillis: () -> Long = ::nowEpochMs): Frame.TimeSet? {
        val before = nowMillis()
        val elapsed = TimeSource.Monotonic.markNow()
        val snapshot = coroutineScope {
            val waiting = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeoutOrNull(STATUS_CLOCK_TIMEOUT_MS) { status.first() }
            }
            try {
                requestStatus()
                waiting.await() ?: try {
                    // Native characteristic/LAN reads can return cached snapshots. Quality and
                    // age still guide initialization/maintenance; cached UTC cannot measure drift.
                    readStatus().copy(utcEpochMillis = null)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            } finally {
                waiting.cancel()
            }
        }
        val delayMillis = elapsed.elapsedNow().inWholeMilliseconds.coerceAtLeast(0)
        val midpoint = before + delayMillis / 2
        val decision = SmartSyncPolicy.decide(snapshot, midpoint, (delayMillis + 1) / 2)
        return if (decision.action == ClockSyncAction.SET_TIME) synchronizeClock(nowMillis) else null
    }

    /** `FLUSH` → `FLUSHED` or `ERROR` 10. Finalizes the RAM batch into a short file. */
    suspend fun flush(): Frame.Flushed = single(Request.Flush, "FLUSH")

    /** `STATUS` → a `status` notification only; no `response` frame is defined. */
    suspend fun requestStatus() = link.send(RequestSpec.of(Request.Status))

    // ------------------------------------------------------------------------ v2

    /** `GET_CONFIG` → `CONFIG`. */
    suspend fun getConfig(): ConfigSnapshot = config(Request.GetConfig, "GET_CONFIG")

    /** Null on firmware which ignores the page selector; malformed-request also means unsupported. */
    suspend fun getLocation(): space.opensensor.data.protocol.LocationConfig? = try {
        config(Request.GetLocation, "GET_CONFIG location").config.location
    } catch (e: DeviceErrorException) {
        if (e.protocolError == ProtocolError.MALFORMED_REQUEST || e.protocolError == ProtocolError.UNKNOWN_OPCODE) null else throw e
    }

    /**
     * `SET_CONFIG key=value…` → `CONFIG` (the new state) or `ERROR` 12 ([InvalidConfigException]
     * with the offending key; nothing changed). Values are validated by [ConfigPatch] before send.
     *
     * A patch that [ConfigPatch.carriesSecret] goes only over a link whose real driver
     * [TransportKind.carriesSecrets], else [SecretNeedsBluetoothException] and nothing is written.
     * The check reads [LinkSession.kind], the pipe the bytes would take, not [transport]'s label.
     */
    suspend fun setConfig(patch: ConfigPatch): ConfigSnapshot {
        require(!patch.isEmpty) { "empty config patch" }
        if (patch.carriesSecret && !link.kind.carriesSecrets) throw SecretNeedsBluetoothException(link.kind)
        return config(Request.SetConfig(patch), "SET_CONFIG")
    }

    /** One `CONFIG`, or `CONFIG_CHUNK` slices that end the reply once they add up. */
    private suspend fun config(request: Request, label: String): ConfigSnapshot {
        val accumulator = ConfigChunkAccumulator()
        val frame = request(request) { frame ->
            when (frame) {
                is Frame.Config -> frame
                is Frame.ConfigChunk -> try {
                    accumulator.add(frame)
                } catch (e: Exception) {
                    throw ProtocolViolationException("CONFIG_CHUNK invalid: ${e.message}")
                }
                else -> throw unexpected(label, frame)
            }
        }
        val parsed = try {
            DeviceConfig.parse(frame.json)
        } catch (e: Exception) {
            throw ProtocolViolationException("CONFIG JSON did not parse: ${e.message}")
        }
        return ConfigSnapshot(parsed, frame.rebootRequired, frame.json, nowEpochMs())
    }

    /**
     * `WIFI_SCAN` → `WIFI_AP` × n, then `WIFI_SCAN_END`. Takes 2–4 s on the device. `ERROR` 15 (or a
     * non-zero end status) surfaces as [WifiUnavailableException].
     */
    suspend fun wifiScan(): WifiScanResult {
        val aps = ArrayList<Frame.WifiAp>()
        val end = request(Request.WifiScan) { frame ->
            when (frame) {
                is Frame.WifiAp -> {
                    aps += frame
                    null
                }
                is Frame.WifiScanEnd -> frame
                else -> throw unexpected("WIFI_SCAN", frame)
            }
        }
        if (!end.ok) throw DeviceErrorException.of(Frame.Error(Opcode.WIFI_SCAN, end.status, "WIFI_SCAN_END status"))
        return WifiScanResult(aps, end)
    }

    /**
     * `REBOOT` → `REBOOTING delay_ms`; the device finalizes its RAM batch and restarts. The caller
     * should disconnect within `delayMs` and reconnect afterwards.
     */
    suspend fun reboot(): Frame.Rebooting = single(Request.Reboot, "REBOOT")

    /** `LOG_TAIL max_bytes` → `LOG` × n, then `LOG_END`. Text for humans, not an API. */
    suspend fun logTail(maxBytes: Int = LOG_RING_BYTES): LogTail {
        val slices = ArrayList<ByteArray>()
        val end = request(Request.LogTail(maxBytes.coerceIn(0, 0xFFFF))) { frame ->
            when (frame) {
                is Frame.Log -> {
                    slices += frame.bytes
                    null
                }
                is Frame.LogEnd -> frame
                else -> throw unexpected("LOG_TAIL", frame)
            }
        }
        val bytes = concat(slices)
        return LogTail(bytes.decodeToString(), end.total, end.returned, bytes.size)
    }

    /**
     * `GET_TOKEN` → `TOKEN port, token`. BLE only: over LAN the device answers `ERROR` 13
     * ([NotAllowedOnLinkException]). The token is a bearer secret — store it, never log it.
     */
    suspend fun getToken(): Frame.Token = single(Request.GetToken, "GET_TOKEN")

    /** One `GET_DESCRIPTOR` slice of the board template (#191). */
    suspend fun descriptorChunk(offset: Long, length: Int): Frame.DescriptorChunk =
        single(Request.GetDescriptor(offset, length), "GET_DESCRIPTOR")

    /**
     * The whole board template the firmware serves, reassembled by [DescriptorDownload]. Throws
     * without sending anything unless the link is open, which on BLE includes the bond. Throws
     * [NoDescriptorException] when the firmware has none. The caller verifies the bytes.
     */
    suspend fun downloadDescriptor(): ByteArray {
        val ready = link.state.value as? LinkState.Ready ?: throw IllegalStateException("GET_DESCRIPTOR needs an open link")
        return DescriptorDownload.fetch(DescriptorDownload.chunkLength(ready.info.payloadMax), ::descriptorChunk)
    }

    /**
     * Advanced → Debug "raw command": write arbitrary bytes and return the reply frames the link
     * routes to that opcode, through its terminal frame. An unknown opcode ends with the device's
     * `ERROR`. Without a terminal within [quiet] the frames so far are returned, and the link
     * drains the rest within its [space.opensensor.link.DrainBound] (with `ABORT` on proto 3).
     * Frames the link cannot decode are dropped there and never reach this list.
     */
    suspend fun rawCommand(request: ByteArray, quiet: Duration = RAW_QUIET): List<Pair<ByteArray, Frame?>> {
        require(request.isNotEmpty() && request.size <= RawCommandPolicy.MAX_BYTES) { "request must be 1..512 bytes" }
        // The data layer enforces the denylist itself, so a UI path that forgets its own guard
        // still cannot reboot the device or change its configuration without a confirmation.
        val op = request[0].toInt() and 0xFF
        if (RawCommandPolicy.isRefused(request)) throw RawCommandRefusedException(op)
        val got = ArrayList<Pair<ByteArray, Frame?>>()
        val spec = RequestSpec(op, request, firstFrameTimeout = quiet, interFrameTimeout = quiet, expectsResponse = op != Opcode.STATUS)
        try {
            if (!spec.expectsResponse) {
                link.send(spec)
                return got
            }
            link.exchangeRaw(spec) { raw, frame, terminal ->
                got += raw to frame
                if (terminal) Unit else null
            }
        } catch (_: LinkTimeoutException) {
            // Quiet: what arrived is the answer. The link drains the rest.
        } finally {
            frames.flush()
        }
        return got
    }

    /**
     * Run one request on the link. An `ERROR` routed to it ends the reply, and becomes the typed
     * [DeviceErrorException]. [onFrame] returns the result on the terminal frame, null before it.
     */
    private suspend fun <R : Any> request(request: Request, oweCloseIfUnsent: Int? = null, onFrame: suspend (Frame) -> R?): R = try {
        link.exchange(RequestSpec.of(request), oweCloseIfUnsent) { frame, _ ->
            if (frame is Frame.Error) throw DeviceErrorException.of(frame)
            onFrame(frame)
        }
    } finally {
        frames.flush()
    }

    private suspend inline fun <reified F : Frame> single(request: Request, label: String, oweCloseIfUnsent: Int? = null): F =
        request(request, oweCloseIfUnsent) { frame -> frame as? F ?: throw unexpected(label, frame) }

    private fun concat(slices: List<ByteArray>): ByteArray {
        val out = ByteArray(slices.sumOf { it.size })
        var at = 0
        for (slice in slices) {
            slice.copyInto(out, at)
            at += slice.size
        }
        return out
    }

    private fun unexpected(request: String, frame: Frame) =
        ProtocolViolationException("unexpected ${frame::class.simpleName} frame in response to $request")

    companion object {
        private const val STATUS_CLOCK_TIMEOUT_MS = 5_000L

        /** The device's serial log ring. */
        const val LOG_RING_BYTES = 8192

        /** How long a raw command waits for a first or next frame before it shows what came. */
        val RAW_QUIET: Duration = 1.5.seconds

        /**
         * Opcodes the debug raw command never sends: `REBOOT` restarts the logger and `SET_CONFIG`
         * can rotate the token, forget bonds or disable the passkey. Both need the Device screen's
         * confirmation (root `AGENTS.md`).
         */
        val RAW_COMMAND_DENYLIST: Set<Int> get() = RawCommandPolicy.DENYLIST

        /**
         * A view over [transport] with its own unopened [LinkSession], until the platform drivers
         * land (#57, #58). Call [LinkSession.open] once the transport may connect. [info] stands in
         * for the `info` read on a transport that cannot read it. The link runs in [scope] until it
         * closes, and [LinkSession.close] disconnects the transport. [openTimeout] covers a Bluetooth
         * connect plus the bond, where the user types the PIN, so it outlasts the transport's own.
         */
        fun over(
            transport: DeviceTransport,
            scope: CoroutineScope,
            frames: FrameRecorder = FrameRecorder(),
            log: LinkLog = LinkLog.NONE,
            info: (suspend () -> DeviceInfo)? = null,
            openTimeout: Duration = OPEN_TIMEOUT,
        ): DeviceSession {
            val driver = TransportLinkDriver(transport, scope, frames, info)
            return DeviceSession(LinkSession(driver, scope, openTimeout = openTimeout, log = log), transport, frames)
        }

        /** Above a Bluetooth connect (30 s) plus a bond (120 s), so the transport's typed timeout wins. */
        val OPEN_TIMEOUT: Duration = 180.seconds
    }
}
