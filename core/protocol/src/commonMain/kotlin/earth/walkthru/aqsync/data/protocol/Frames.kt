package earth.walkthru.aqsync.data.protocol

/**
 * Binary `control` requests and `response` frames from docs/ble-sync-protocol.md (protocol v3).
 * Protocol 3 widens `LIST_END.count` to u32 and adds `GET_DIAG`, `ABORT`, `BYE`, `DIAG` and
 * `ABORTED`. A proto 2 `LIST_END` is rejected. `GET_DESCRIPTOR` and `DESCRIPTOR_CHUNK` serve the
 * board template.
 *
 * Every multi-byte integer is little-endian. Text is UTF-8 with no terminator; its length is
 * implied by the frame length. The first byte of a request is the opcode; the first byte of a
 * response frame is the frame type.
 */
object Opcode {
    const val LIST: Int = 0x01
    const val OPEN: Int = 0x02
    const val READ: Int = 0x03
    const val CLOSE: Int = 0x04
    const val SET_TIME: Int = 0x05
    const val FLUSH: Int = 0x06
    const val STATUS: Int = 0x07

    // Protocol v2 (device configuration, control, LAN token).
    const val GET_CONFIG: Int = 0x08
    const val SET_CONFIG: Int = 0x09
    const val WIFI_SCAN: Int = 0x0A
    const val REBOOT: Int = 0x0B
    const val LOG_TAIL: Int = 0x0C
    const val GET_TOKEN: Int = 0x0D

    // Protocol v3 (disconnect diagnostics, abort).
    const val GET_DIAG: Int = 0x0E
    const val ABORT: Int = 0x0F

    /** One slice of the board template. */
    const val GET_DESCRIPTOR: Int = 0x11

    fun name(op: Int): String = when (op) {
        LIST -> "LIST"
        OPEN -> "OPEN"
        READ -> "READ"
        CLOSE -> "CLOSE"
        SET_TIME -> "SET_TIME"
        FLUSH -> "FLUSH"
        STATUS -> "STATUS"
        GET_CONFIG -> "GET_CONFIG"
        SET_CONFIG -> "SET_CONFIG"
        WIFI_SCAN -> "WIFI_SCAN"
        REBOOT -> "REBOOT"
        LOG_TAIL -> "LOG_TAIL"
        GET_TOKEN -> "GET_TOKEN"
        GET_DIAG -> "GET_DIAG"
        ABORT -> "ABORT"
        GET_DESCRIPTOR -> "GET_DESCRIPTOR"
        else -> "0x${op.toString(16).padStart(2, '0')}"
    }
}

object FrameType {
    const val FILE: Int = 0x10
    const val LIST_END: Int = 0x11
    const val OPENED: Int = 0x20
    const val CHUNK: Int = 0x21
    const val READ_END: Int = 0x22
    const val CLOSED: Int = 0x23
    const val TIME_SET: Int = 0x30
    const val FLUSHED: Int = 0x31

    // Protocol v2.
    const val CONFIG: Int = 0x40
    const val WIFI_AP: Int = 0x41
    const val WIFI_SCAN_END: Int = 0x42
    const val REBOOTING: Int = 0x43
    const val LOG: Int = 0x44
    const val LOG_END: Int = 0x45
    const val TOKEN: Int = 0x46

    /** LAN only: handshake answer. */
    const val HELLO: Int = 0x47

    /** LAN only: pushed `status` JSON (the BLE `status` notification's equivalent). */
    const val STATUS: Int = 0x48

    /** LAN only: pushed `live` JSON. */
    const val LIVE: Int = 0x49

    /** BLE-only slices of a CONFIG JSON body when one notification cannot carry it. */
    const val CONFIG_CHUNK: Int = 0x4A

    // Protocol v3.

    /** LAN only: the device's reason for closing, sent just before the socket closes. */
    const val BYE: Int = 0x4B
    const val DIAG: Int = 0x4C
    const val ABORTED: Int = 0x4D

    /** Only ever a reply to `GET_DESCRIPTOR`. */
    const val DESCRIPTOR_CHUNK: Int = 0x4F
    const val ERROR: Int = 0x7F

    fun name(type: Int): String = when (type) {
        FILE -> "FILE"
        LIST_END -> "LIST_END"
        OPENED -> "OPENED"
        CHUNK -> "CHUNK"
        READ_END -> "READ_END"
        CLOSED -> "CLOSED"
        TIME_SET -> "TIME_SET"
        FLUSHED -> "FLUSHED"
        CONFIG -> "CONFIG"
        WIFI_AP -> "WIFI_AP"
        WIFI_SCAN_END -> "WIFI_SCAN_END"
        REBOOTING -> "REBOOTING"
        LOG -> "LOG"
        LOG_END -> "LOG_END"
        TOKEN -> "TOKEN"
        HELLO -> "HELLO"
        STATUS -> "STATUS"
        CONFIG_CHUNK -> "CONFIG_CHUNK"
        LIVE -> "LIVE"
        BYE -> "BYE"
        DIAG -> "DIAG"
        ABORTED -> "ABORTED"
        DESCRIPTOR_CHUNK -> "DESCRIPTOR_CHUNK"
        ERROR -> "ERROR"
        else -> "0x${type.toString(16).padStart(2, '0')}"
    }
}

/** Error codes carried by `ERROR` frames and by `READ_END.status`. */
enum class ProtocolError(val code: Int, val label: String) {
    MALFORMED_REQUEST(1, "malformed request"),
    INVALID_NAME(2, "invalid name"),
    NOT_FINALIZED_PARQUET(3, "not a finalized Parquet file"),
    OPEN_FAILED(4, "open failed"),
    BAD_HANDLE(5, "bad handle"),
    RANGE_OUTSIDE_FILE(6, "range outside file"),
    BUSY(7, "busy (command queue full or transfer in progress)"),
    STORAGE_UNAVAILABLE(8, "storage unavailable"),
    INVALID_EPOCH(9, "invalid epoch (outside 2020–2100)"),
    NOTHING_TO_FLUSH(10, "nothing to flush"),
    UNKNOWN_OPCODE(11, "unknown opcode"),
    INVALID_CONFIG(12, "invalid config"),
    NOT_ALLOWED_ON_LINK(13, "not allowed on this link"),
    AUTH_FAILED(14, "authentication failed"),
    WIFI_UNAVAILABLE(15, "Wi-Fi unavailable"),
    ABORTED(16, "aborted"),
    NO_DESCRIPTOR(17, "no descriptor");

    companion object {
        fun fromCode(code: Int): ProtocolError? = entries.firstOrNull { it.code == code }
        fun describe(code: Int): String = fromCode(code)?.label ?: "unknown error code $code"
    }
}

/** A `control` request, ready to be written to the characteristic. */
sealed interface Request {
    val opcode: Int
    fun encode(): ByteArray

    data object List : Request {
        override val opcode: Int = Opcode.LIST
        override fun encode(): ByteArray = byteArrayOf(opcode.toByte())
    }

    data class Open(val name: String) : Request {
        override val opcode: Int = Opcode.OPEN
        override fun encode(): ByteArray {
            val utf8 = name.encodeToByteArray()
            return WireBuffer.allocate(1 + utf8.size)
                .put(opcode.toByte()).put(utf8).array()
        }
    }

    /** `handle` u16, `offset` u32, `length` u32. */
    data class Read(val handle: Int, val offset: Long, val length: Long) : Request {
        override val opcode: Int = Opcode.READ
        override fun encode(): ByteArray = WireBuffer.allocate(11)
            .put(opcode.toByte())
            .putShort(handle.toShort())
            .putInt(offset.toInt())
            .putInt(length.toInt())
            .array()
    }

    data class Close(val handle: Int) : Request {
        override val opcode: Int = Opcode.CLOSE
        override fun encode(): ByteArray = WireBuffer.allocate(3)
            .put(opcode.toByte()).putShort(handle.toShort()).array()
    }

    /** `epoch_s` i64 with optional `subsecond_us` u32 for precision-aware firmware. */
    data class SetTime(val epochSeconds: Long, val subsecondMicros: Int? = null) : Request {
        init { require(subsecondMicros == null || subsecondMicros in 0..999_999) }
        override val opcode: Int = Opcode.SET_TIME
        override fun encode(): ByteArray = WireBuffer.allocate(if (subsecondMicros == null) 9 else 13)
            .put(opcode.toByte()).putLong(epochSeconds)
            .also { buffer -> subsecondMicros?.let { buffer.putInt(it) } }.array()
    }

    data object Flush : Request {
        override val opcode: Int = Opcode.FLUSH
        override fun encode(): ByteArray = byteArrayOf(opcode.toByte())
    }

    data object Status : Request {
        override val opcode: Int = Opcode.STATUS
        override fun encode(): ByteArray = byteArrayOf(opcode.toByte())
    }

    // ------------------------------------------------------------------ v2

    data object GetConfig : Request {
        override val opcode: Int = Opcode.GET_CONFIG
        override fun encode(): ByteArray = byteArrayOf(opcode.toByte())
    }

    /** Location configuration page. Legacy GET_CONFIG remains its one-byte request. */
    data object GetLocation : Request {
        override val opcode: Int = Opcode.GET_CONFIG
        override fun encode(): ByteArray = byteArrayOf(opcode.toByte(), 1)
    }

    /** `key=value` lines, utf8, `\n`-separated. Build the text with [ConfigPatch]. */
    data class SetConfig(val patch: ConfigPatch) : Request {
        override val opcode: Int = Opcode.SET_CONFIG
        override fun encode(): ByteArray {
            val utf8 = patch.encode().encodeToByteArray()
            return WireBuffer.allocate(1 + utf8.size).put(opcode.toByte()).put(utf8).array()
        }

        /** Never echo the PSK. */
        override fun toString(): String = "SetConfig(${patch.describe()})"
    }

    data object WifiScan : Request {
        override val opcode: Int = Opcode.WIFI_SCAN
        override fun encode(): ByteArray = byteArrayOf(opcode.toByte())
    }

    data object Reboot : Request {
        override val opcode: Int = Opcode.REBOOT
        override fun encode(): ByteArray = byteArrayOf(opcode.toByte())
    }

    /** `max_bytes` u16; the device clips to its ring size (8192). */
    data class LogTail(val maxBytes: Int) : Request {
        init {
            require(maxBytes in 0..0xFFFF) { "max_bytes must fit u16" }
        }

        override val opcode: Int = Opcode.LOG_TAIL
        override fun encode(): ByteArray = WireBuffer.allocate(3)
            .put(opcode.toByte()).putShort(maxBytes.toShort()).array()
    }

    /** BLE only; the device answers `ERROR` 13 over LAN. */
    data object GetToken : Request {
        override val opcode: Int = Opcode.GET_TOKEN
        override fun encode(): ByteArray = byteArrayOf(opcode.toByte())
    }

    // ------------------------------------------------------------------ v3

    /** Empty body. Answered by `DIAG`, or `ERROR` 7 `mtu` on a link narrower than 38 bytes. */
    data object GetDiag : Request {
        override val opcode: Int = Opcode.GET_DIAG
        override fun encode(): ByteArray = byteArrayOf(opcode.toByte())
    }

    /** Empty body. Answered by `ABORTED` with the opcode it stopped, 0 when idle. */
    data object Abort : Request {
        override val opcode: Int = Opcode.ABORT
        override fun encode(): ByteArray = byteArrayOf(opcode.toByte())
    }

    /** `offset` u32, `length` u16 of at least 1. Answered by one `DESCRIPTOR_CHUNK`. */
    data class GetDescriptor(val offset: Long, val length: Int) : Request {
        init {
            require(offset in 0..0xFFFF_FFFFL) { "offset must fit u32" }
            require(length in 1..0xFFFF) { "length must be 1 to 65535" }
        }

        override val opcode: Int = Opcode.GET_DESCRIPTOR
        override fun encode(): ByteArray = WireBuffer.allocate(7)
            .put(opcode.toByte()).putInt(offset.toInt()).putShort(length.toShort()).array()
    }
}

/** `WIFI_AP.auth` values. */
enum class WifiAuth(val code: Int, val label: String, val secured: Boolean) {
    OPEN(0, "open", false),
    WEP(1, "WEP", true),
    WPA(2, "WPA", true),
    WPA2(3, "WPA2", true),
    WPA_WPA2(4, "WPA/WPA2", true),
    WPA2_ENTERPRISE(5, "WPA2-Enterprise", true),
    WPA3(6, "WPA3", true),
    WPA2_WPA3(7, "WPA2/WPA3", true),
    OTHER(255, "other", true);

    companion object {
        fun fromCode(code: Int): WifiAuth = entries.firstOrNull { it.code == code } ?: OTHER
    }
}

/** A decoded `response` notification. */
sealed interface Frame {
    /** `bytes` u32, `name` utf8. */
    data class File(val bytes: Long, val name: String) : Frame

    /** `count` u32, `partials` u16, `sd_kib` u32, `sd_used_kib` u32. 15 bytes with the type. */
    data class ListEnd(val count: Long, val partials: Int, val sdKib: Long, val sdUsedKib: Long) : Frame

    /** `handle` u16, `size` u32, `crc32` u32, `name` utf8. */
    data class Opened(val handle: Int, val size: Long, val crc32: Long, val name: String) : Frame

    /** `handle` u16, `offset` u32, `payload`. */
    class Chunk(val handle: Int, val offset: Long, val payload: ByteArray) : Frame {
        override fun toString(): String = "Chunk(handle=$handle, offset=$offset, len=${payload.size})"
    }

    /** `handle` u16, `next_offset` u32, `status` u8 (0 = ok, else error code). */
    data class ReadEnd(val handle: Int, val nextOffset: Long, val status: Int) : Frame {
        val ok: Boolean get() = status == 0
    }

    data class Closed(val handle: Int) : Frame

    /** `epoch_s` i64, `monotonic_us` i64, optionally acknowledged `subsecond_us` u32. */
    data class TimeSet(val epochSeconds: Long, val monotonicMicros: Long, val subsecondMicros: Int? = null) : Frame {
        val epochNanos: Long get() = epochSeconds * 1_000_000_000L + (subsecondMicros ?: 0) * 1000L
    }

    /** `rows` u16 written, `fin` u32 files finalized so far. */
    data class Flushed(val rows: Int, val filesFinalized: Long) : Frame

    // ------------------------------------------------------------------ v2

    /** `flags` u8 (bit 0 = reboot required for a pending `ble.*` change), JSON ≤ 480 bytes. */
    data class Config(val flags: Int, val json: String) : Frame {
        val rebootRequired: Boolean get() = flags and 0x01 != 0
    }

    /** `flags` u8, total JSON bytes u16 LE, offset u16 LE, raw UTF-8 slice. */
    class ConfigChunk(val flags: Int, val total: Int, val offset: Int, val bytes: ByteArray) : Frame {
        override fun toString(): String = "ConfigChunk(flags=$flags, total=$total, offset=$offset, len=${bytes.size})"
    }

    /** `rssi` i8, `auth` u8, `channel` u8, `ssid` utf8. */
    data class WifiAp(val rssi: Int, val auth: Int, val channel: Int, val ssid: String) : Frame {
        val wifiAuth: WifiAuth get() = WifiAuth.fromCode(auth)
    }

    /** `count` u16, `status` u8 (0 ok, else error code). */
    data class WifiScanEnd(val count: Int, val status: Int) : Frame {
        val ok: Boolean get() = status == 0
    }

    /** `delay_ms` u16 before the device restarts. */
    data class Rebooting(val delayMs: Int) : Frame

    /**
     * A slice of the serial log ring, in order. Kept as raw bytes: a slice boundary may fall inside
     * a multi-byte UTF-8 sequence, so decode only after concatenating every slice.
     */
    class Log(val bytes: ByteArray) : Frame {
        override fun toString(): String = "Log(len=${bytes.size})"
    }

    /** `total` u32 bytes logged since boot, `returned` u16. */
    data class LogEnd(val total: Long, val returned: Int) : Frame

    /** `port` u16, `token` 32 bytes. The token is a bearer secret: [toString] redacts it. */
    class Token(val port: Int, val token: ByteArray) : Frame {
        init {
            require(token.size == LAN_TOKEN_BYTES) { "token must be $LAN_TOKEN_BYTES bytes" }
        }

        override fun toString(): String = "Token(port=$port, token=<redacted>)"
    }

    /** LAN only: `proto` u8, `payload_max` u16, info JSON. */
    data class Hello(val proto: Int, val payloadMax: Int, val infoJson: String) : Frame

    /** LAN only, push: status JSON. */
    data class StatusPush(val json: String) : Frame

    /** LAN only, push: live JSON. */
    data class LivePush(val json: String) : Frame

    // ------------------------------------------------------------------ v3

    /**
     * LAN only, sent once before the device closes the socket. `reason` u8, `generation` u32,
     * `err` i16, `queued` u16, `uptime_s` u32. It can be lost, so EOF without BYE means unknown.
     */
    data class Bye(val reason: Int, val generation: Long, val err: Int, val queued: Int, val uptimeSeconds: Long) : Frame {
        val disconnectReason: DisconnectReason get() = DisconnectReason.fromCode(reason)
    }

    /**
     * `boot_seq` u16, one saturating u16 counter per [DisconnectReason] code, `n` u8, then `n`
     * events oldest first.
     */
    data class Diag(val bootSeq: Int, val counts: List<Int>, val events: List<DisconnectEvent>) : Frame {
        /** Counter for one reason, 0 when the device reported fewer slots. */
        fun count(reason: DisconnectReason): Int = counts.getOrElse(reason.code) { 0 }
    }

    /** `op` u8, the opcode `ABORT` stopped, 0 when nothing was running. */
    data class Aborted(val op: Int) : Frame

    /** `total` u32 template bytes, `offset` u32 echoing the request, then a slice of the template. */
    class DescriptorChunk(val total: Long, val offset: Long, val bytes: ByteArray) : Frame {
        override fun toString(): String = "DescriptorChunk(total=$total, offset=$offset, len=${bytes.size})"
    }

    /** `op` u8, `code` u8, `detail` utf8. */
    data class Error(val op: Int, val code: Int, val detail: String) : Frame {
        val error: ProtocolError? get() = ProtocolError.fromCode(code)
        fun describe(): String = buildString {
            append(Opcode.name(op)).append(" failed: ").append(ProtocolError.describe(code))
            if (detail.isNotEmpty()) append(" (").append(detail).append(')')
        }
    }
}

class FrameDecodeException(message: String) : Exception(message)

/** Size of the LAN bearer token carried by `TOKEN` and the `"OSS1"` handshake. */
const val LAN_TOKEN_BYTES: Int = 32

object FrameCodec {
    /** Decode one `response` notification. Throws [FrameDecodeException] on truncated/unknown frames. */
    fun decode(raw: ByteArray): Frame {
        if (raw.isEmpty()) throw FrameDecodeException("empty frame")
        val buf = WireBuffer.wrap(raw)
        val type = buf.get().toInt() and 0xFF
        return try {
            when (type) {
                FrameType.FILE -> Frame.File(bytes = buf.u32(), name = buf.restUtf8())
                FrameType.LIST_END -> {
                    if (raw.size != LIST_END_BYTES) throw FrameDecodeException("invalid LIST_END length ${raw.size}")
                    Frame.ListEnd(
                        count = buf.u32(),
                        partials = buf.u16(),
                        sdKib = buf.u32(),
                        sdUsedKib = buf.u32()
                    )
                }
                FrameType.OPENED -> Frame.Opened(
                    handle = buf.u16(),
                    size = buf.u32(),
                    crc32 = buf.u32(),
                    name = buf.restUtf8()
                )
                FrameType.CHUNK -> {
                    val handle = buf.u16()
                    val offset = buf.u32()
                    val payload = ByteArray(buf.remaining()).also { buf.get(it) }
                    Frame.Chunk(handle, offset, payload)
                }
                FrameType.READ_END -> Frame.ReadEnd(handle = buf.u16(), nextOffset = buf.u32(), status = buf.u8())
                FrameType.CLOSED -> Frame.Closed(handle = buf.u16())
                FrameType.TIME_SET -> {
                    if (raw.size != 17 && raw.size != 21) throw FrameDecodeException("invalid TIME_SET length")
                    val seconds = buf.long
                    val mono = buf.long
                    val micros = if (buf.remaining() == 4) buf.u32() else null
                    if (micros != null && micros > 999_999) throw FrameDecodeException("invalid TIME_SET fraction")
                    Frame.TimeSet(seconds, mono, micros?.toInt())
                }
                FrameType.FLUSHED -> Frame.Flushed(rows = buf.u16(), filesFinalized = buf.u32())
                FrameType.CONFIG -> Frame.Config(flags = buf.u8(), json = buf.restUtf8())
                FrameType.CONFIG_CHUNK -> Frame.ConfigChunk(
                    flags = buf.u8(),
                    total = buf.u16(),
                    offset = buf.u16(),
                    bytes = ByteArray(buf.remaining()).also { buf.get(it) },
                )
                FrameType.WIFI_AP -> Frame.WifiAp(
                    rssi = buf.get().toInt(),
                    auth = buf.u8(),
                    channel = buf.u8(),
                    ssid = buf.restUtf8()
                )
                FrameType.WIFI_SCAN_END -> Frame.WifiScanEnd(count = buf.u16(), status = buf.u8())
                FrameType.REBOOTING -> Frame.Rebooting(delayMs = buf.u16())
                FrameType.LOG -> Frame.Log(ByteArray(buf.remaining()).also { buf.get(it) })
                FrameType.LOG_END -> Frame.LogEnd(total = buf.u32(), returned = buf.u16())
                FrameType.TOKEN -> {
                    val port = buf.u16()
                    if (buf.remaining() != LAN_TOKEN_BYTES) {
                        throw FrameDecodeException("TOKEN carries ${buf.remaining()} token bytes, expected $LAN_TOKEN_BYTES")
                    }
                    Frame.Token(port, ByteArray(LAN_TOKEN_BYTES).also { buf.get(it) })
                }
                FrameType.HELLO -> Frame.Hello(proto = buf.u8(), payloadMax = buf.u16(), infoJson = buf.restUtf8())
                FrameType.STATUS -> Frame.StatusPush(buf.restUtf8())
                FrameType.LIVE -> Frame.LivePush(buf.restUtf8())
                FrameType.BYE -> {
                    if (raw.size != BYE_BYTES) throw FrameDecodeException("invalid BYE length ${raw.size}")
                    Frame.Bye(
                        reason = buf.u8(),
                        generation = buf.u32(),
                        err = buf.short.toInt(),
                        queued = buf.u16(),
                        uptimeSeconds = buf.u32(),
                    )
                }
                FrameType.DIAG -> {
                    val bootSeq = buf.u16()
                    val counts = List(DisconnectReason.COUNT) { buf.u16() }
                    val n = buf.u8()
                    if (buf.remaining() != n * DisconnectEvent.BYTES) {
                        throw FrameDecodeException("DIAG declares $n events but carries ${buf.remaining()} bytes")
                    }
                    val events = List(n) { i -> DisconnectEvent.decode(raw, DIAG_HEADER_BYTES + i * DisconnectEvent.BYTES) }
                    Frame.Diag(bootSeq, counts, events)
                }
                FrameType.ABORTED -> {
                    if (raw.size != 2) throw FrameDecodeException("invalid ABORTED length ${raw.size}")
                    Frame.Aborted(op = buf.u8())
                }
                FrameType.DESCRIPTOR_CHUNK -> Frame.DescriptorChunk(
                    total = buf.u32(),
                    offset = buf.u32(),
                    bytes = ByteArray(buf.remaining()).also { buf.get(it) },
                )
                FrameType.ERROR -> Frame.Error(op = buf.u8(), code = buf.u8(), detail = buf.restUtf8())
                else -> throw FrameDecodeException("unknown frame type 0x${type.toString(16).padStart(2, '0')}")
            }
        } catch (e: WireUnderflowException) {
            throw FrameDecodeException("truncated frame type 0x${type.toString(16).padStart(2, '0')} (${raw.size} bytes)")
        }
    }

    /**
     * Encode a response frame. The phone never sends these; the encoder exists for the in-app
     * fake device and for round-trip tests of the decoder.
     */
    fun encode(frame: Frame): ByteArray {
        fun le(size: Int) = WireBuffer.allocate(size)
        return when (frame) {
            is Frame.File -> {
                val n = frame.name.encodeToByteArray()
                le(5 + n.size).put(FrameType.FILE.toByte()).putInt(frame.bytes.toInt()).put(n).array()
            }
            is Frame.ListEnd -> le(LIST_END_BYTES).put(FrameType.LIST_END.toByte())
                .putInt(frame.count.toInt()).putShort(frame.partials.toShort())
                .putInt(frame.sdKib.toInt()).putInt(frame.sdUsedKib.toInt()).array()
            is Frame.Opened -> {
                val n = frame.name.encodeToByteArray()
                le(11 + n.size).put(FrameType.OPENED.toByte()).putShort(frame.handle.toShort())
                    .putInt(frame.size.toInt()).putInt(frame.crc32.toInt()).put(n).array()
            }
            is Frame.Chunk -> le(7 + frame.payload.size).put(FrameType.CHUNK.toByte())
                .putShort(frame.handle.toShort()).putInt(frame.offset.toInt()).put(frame.payload).array()
            is Frame.ReadEnd -> le(8).put(FrameType.READ_END.toByte()).putShort(frame.handle.toShort())
                .putInt(frame.nextOffset.toInt()).put(frame.status.toByte()).array()
            is Frame.Closed -> le(3).put(FrameType.CLOSED.toByte()).putShort(frame.handle.toShort()).array()
            is Frame.TimeSet -> le(if (frame.subsecondMicros == null) 17 else 21).put(FrameType.TIME_SET.toByte())
                .putLong(frame.epochSeconds).putLong(frame.monotonicMicros)
                .also { buffer -> frame.subsecondMicros?.let { buffer.putInt(it) } }.array()
            is Frame.Flushed -> le(7).put(FrameType.FLUSHED.toByte())
                .putShort(frame.rows.toShort()).putInt(frame.filesFinalized.toInt()).array()
            is Frame.Error -> {
                val d = frame.detail.encodeToByteArray()
                le(3 + d.size).put(FrameType.ERROR.toByte()).put(frame.op.toByte()).put(frame.code.toByte()).put(d).array()
            }
            is Frame.Config -> {
                val j = frame.json.encodeToByteArray()
                le(2 + j.size).put(FrameType.CONFIG.toByte()).put(frame.flags.toByte()).put(j).array()
            }
            is Frame.ConfigChunk -> le(6 + frame.bytes.size).put(FrameType.CONFIG_CHUNK.toByte())
                .put(frame.flags.toByte()).putShort(frame.total.toShort()).putShort(frame.offset.toShort())
                .put(frame.bytes).array()
            is Frame.WifiAp -> {
                val s = frame.ssid.encodeToByteArray()
                le(4 + s.size).put(FrameType.WIFI_AP.toByte()).put(frame.rssi.toByte())
                    .put(frame.auth.toByte()).put(frame.channel.toByte()).put(s).array()
            }
            is Frame.WifiScanEnd -> le(4).put(FrameType.WIFI_SCAN_END.toByte())
                .putShort(frame.count.toShort()).put(frame.status.toByte()).array()
            is Frame.Rebooting -> le(3).put(FrameType.REBOOTING.toByte()).putShort(frame.delayMs.toShort()).array()
            is Frame.Log -> le(1 + frame.bytes.size).put(FrameType.LOG.toByte()).put(frame.bytes).array()
            is Frame.LogEnd -> le(7).put(FrameType.LOG_END.toByte()).putInt(frame.total.toInt()).putShort(frame.returned.toShort()).array()
            is Frame.Token -> le(3 + LAN_TOKEN_BYTES).put(FrameType.TOKEN.toByte()).putShort(frame.port.toShort()).put(frame.token).array()
            is Frame.Hello -> {
                val j = frame.infoJson.encodeToByteArray()
                le(4 + j.size).put(FrameType.HELLO.toByte()).put(frame.proto.toByte()).putShort(frame.payloadMax.toShort()).put(j).array()
            }
            is Frame.StatusPush -> {
                val j = frame.json.encodeToByteArray()
                le(1 + j.size).put(FrameType.STATUS.toByte()).put(j).array()
            }
            is Frame.LivePush -> {
                val j = frame.json.encodeToByteArray()
                le(1 + j.size).put(FrameType.LIVE.toByte()).put(j).array()
            }
            is Frame.Bye -> le(BYE_BYTES).put(FrameType.BYE.toByte()).put(frame.reason.toByte())
                .putInt(frame.generation.toInt()).putShort(frame.err.toShort())
                .putShort(frame.queued.toShort()).putInt(frame.uptimeSeconds.toInt()).array()
            is Frame.Diag -> {
                require(frame.counts.size == DisconnectReason.COUNT) { "DIAG needs ${DisconnectReason.COUNT} counts" }
                require(frame.events.size <= 0xFF) { "DIAG carries at most 255 events" }
                val out = le(DIAG_HEADER_BYTES + frame.events.size * DisconnectEvent.BYTES)
                    .put(FrameType.DIAG.toByte()).putShort(frame.bootSeq.toShort())
                frame.counts.forEach { out.putShort(it.toShort()) }
                out.put(frame.events.size.toByte())
                frame.events.forEach { out.put(it.encode()) }
                out.array()
            }
            is Frame.DescriptorChunk -> le(9 + frame.bytes.size).put(FrameType.DESCRIPTOR_CHUNK.toByte())
                .putInt(frame.total.toInt()).putInt(frame.offset.toInt()).put(frame.bytes).array()
            is Frame.Aborted -> le(2).put(FrameType.ABORTED.toByte()).put(frame.op.toByte()).array()
        }
    }

    /** `LIST_END` size with the type byte. Proto 2 sent 13. */
    const val LIST_END_BYTES: Int = 15

    /** `BYE` size with the type byte. The LAN wire adds the two-byte length. */
    const val BYE_BYTES: Int = 14

    /** `DIAG` size with the type byte and no events. */
    const val DIAG_HEADER_BYTES: Int = 4 + 2 * DisconnectReason.COUNT

    /** Frame type byte of a raw frame, or null when empty. Cheap dispatch without a full decode. */
    fun typeOf(raw: ByteArray): Int? = raw.firstOrNull()?.toInt()?.and(0xFF)

    private fun WireBuffer.u8(): Int = get().toInt() and 0xFF
    private fun WireBuffer.u16(): Int = short.toInt() and 0xFFFF
    private fun WireBuffer.u32(): Long = int.toLong() and 0xFFFF_FFFFL
    private fun WireBuffer.restUtf8(): String {
        val bytes = ByteArray(remaining()).also { get(it) }
        return bytes.decodeToString()
    }
}

/** Hex helpers for logs and test fixtures. */
fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

fun String.hexToBytes(): ByteArray {
    val clean = filterNot { it.isWhitespace() }
    require(clean.length % 2 == 0) { "odd hex length" }
    return ByteArray(clean.length / 2) { i -> clean.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
}
