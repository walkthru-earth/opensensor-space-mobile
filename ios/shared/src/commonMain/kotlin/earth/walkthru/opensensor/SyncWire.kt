package earth.walkthru.opensensor

import earth.walkthru.aqsync.data.lan.LanTokenPolicy
import earth.walkthru.aqsync.data.protocol.AqGatt
import earth.walkthru.aqsync.data.protocol.DeviceInfo
import earth.walkthru.aqsync.data.protocol.Frame
import earth.walkthru.aqsync.data.protocol.FrameCodec
import earth.walkthru.aqsync.data.protocol.LanProtocol
import earth.walkthru.aqsync.data.protocol.Opcode
import earth.walkthru.aqsync.data.protocol.ProtoGate
import earth.walkthru.aqsync.data.protocol.ProtocolError
import earth.walkthru.aqsync.data.protocol.Request
import earth.walkthru.aqsync.data.protocol.requireProto3
import earth.walkthru.aqsync.link.DisconnectCause
import earth.walkthru.aqsync.link.peerClosedCause

/**
 * Small Swift facade around the canonical shared codec for the session itself: the token
 * request, STATUS keepalive, LAN handshake and envelope, and reply decoding. File requests
 * (LIST, OPEN, READ, CLOSE) are built only by the shared engine behind [SensorSync].
 * Frame bytes stay binary end to end.
 * The byte-array members are internal. iOS callers use the `NSData` extensions in
 * `iosMain/SyncWireData.kt`, which copy once with `memcpy` instead of building hex strings.
 */
class SyncWire {
    val controlUuid: String get() = AqGatt.CONTROL
    val responseUuid: String get() = AqGatt.RESPONSE
    internal fun tokenFrame(): ByteArray = Request.GetToken.encode()
    internal fun statusFrame(): ByteArray = Request.Status.encode()
    internal fun handshakeFrame(token: ByteArray): ByteArray = LanProtocol.handshake(token)
    internal fun envelopeFrame(body: ByteArray): ByteArray = LanProtocol.encode(body)

    fun identity(json: String): SyncIdentity? = runCatching {
        val info = DeviceInfo.parse(json)
        val station = info.station ?: error("missing station")
        val device = info.dev ?: error("missing hardware identity")
        require(requireProto3(info) is ProtoGate.Accepted && Regex("[0-9a-fA-F]{12}").matches(device))
        require(isStationId(station))
        // Android ReadWindow.kt:21 uses 4096 when max_read is absent. Never above the advertised value.
        val maxRead = info.maxRead ?: DEFAULT_MAX_READ
        require(maxRead > 0)
        SyncIdentity(station, device.lowercase(), maxRead.coerceIn(1, MAX_READ_CAP), info.boot)
    }.getOrNull()

    companion object {
        const val DEFAULT_MAX_READ = 4096
        private const val MAX_READ_CAP = 16384
        private val STATION_ID = Regex("[0-9A-Za-z._-]+")

        /**
         * Same rule as Android (DeviceContinuityPolicy.kt:60, FileArchive.kt:131). The station is one
         * archive directory, so a slash never passes and `.` or `..` alone is refused.
         */
        fun isStationId(station: String): Boolean = STATION_ID.matches(station) && station != "." && station != ".."
    }

    /** Malformed frames return null; no secret bytes are included in errors or descriptions. */
    internal fun decodeFrame(bytes: ByteArray): SyncReply? = runCatching {
        when (val frame = FrameCodec.decode(bytes)) {
            is Frame.File -> SyncReply("file", name = frame.name, size = frame.bytes)
            // The native layer never reads this count. Saturate the u32 into the Int field.
            is Frame.ListEnd -> SyncReply("listEnd", count = frame.count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            is Frame.Opened -> SyncReply("opened", name = frame.name, size = frame.size, crc = frame.crc32, handle = frame.handle)
            is Frame.Chunk -> SyncReply("chunk", handle = frame.handle, offset = frame.offset, bytes = frame.payload)
            is Frame.ReadEnd -> SyncReply("readEnd", handle = frame.handle, offset = frame.nextOffset, code = frame.status)
            is Frame.Closed -> SyncReply("closed", handle = frame.handle)
            is Frame.Token -> SyncReply("token", port = frame.port, bytes = frame.token)
            is Frame.Hello -> SyncReply("hello", code = frame.proto, count = frame.payloadMax, json = frame.infoJson)
            is Frame.LivePush -> SyncReply("live", json = frame.json)
            is Frame.StatusPush -> SyncReply("status", json = frame.json)
            is Frame.Error -> SyncReply("error", code = frame.code, op = frame.op)
            is Frame.Bye -> SyncReply("bye", code = frame.reason)
            // The native socket validates every frame before lending its original bytes to
            // SensorSync. TIME_SET, FLUSHED and config replies belong to that engine too.
            // Reject malformed bytes in FrameCodec, never a valid frame we do not inspect here.
            else -> SyncReply("frame")
        }
    }.getOrNull()
}

/** Immutable transport reply; deliberately redacts secret payloads from toString. */
class SyncReply(
    val kind: String,
    val name: String = "",
    val size: Long = 0,
    val crc: Long = 0,
    val handle: Int = 0,
    val offset: Long = 0,
    val code: Int = 0,
    val count: Int = 0,
    val port: Int = 0,
    /** ERROR only: the opcode of the request it answers, 0 for session errors. */
    val op: Int = 0,
    val json: String = "",
    internal val bytes: ByteArray = EMPTY,
) {
    /**
     * How this frame ends a LAN handshake, the same rule as Android `TcpLinkDriver`. ERROR 14 rejects
     * the stored token. A BYE is read through the shared [peerClosedCause], so auth-failed (3) and
     * token-rotated (10) drop the token by [LanTokenPolicy], sessions-full (4) is busy, and any other
     * BYE is the sensor going away (a reboot, Wi-Fi lost), so the token stays, as for a bare close.
     */
    val refusal: HandshakeRefusal get() = when (kind) {
        "error" -> when (code) {
            ProtocolError.AUTH_FAILED.code -> HandshakeRefusal.TOKEN_REJECTED
            ProtocolError.BUSY.code -> HandshakeRefusal.BUSY
            else -> HandshakeRefusal.INVALID
        }
        "bye" -> byeRefusal(code)
        else -> HandshakeRefusal.NONE
    }

    /** A TOKEN, or the ERROR that answers `GET_TOKEN`. Swift keeps these, the link takes the rest. */
    val answersToken: Boolean get() = kind == "token" || (kind == "error" && op == Opcode.GET_TOKEN)

    override fun toString(): String = "SyncReply($kind)"

    private companion object {
        val EMPTY = ByteArray(0)
    }
}

private val tokenPolicy = LanTokenPolicy()

/** Only the reason byte of a BYE decides its cause, so the other fields are left at zero. */
private fun byeRefusal(reason: Int): HandshakeRefusal {
    val cause = peerClosedCause(Frame.Bye(reason = reason, generation = 0, err = 0, queued = 0, uptimeSeconds = 0))
    return when {
        tokenPolicy.invalidatesToken(cause) -> HandshakeRefusal.TOKEN_REJECTED
        cause == DisconnectCause.Busy -> HandshakeRefusal.BUSY
        else -> HandshakeRefusal.PEER_CLOSED
    }
}

/** What a frame received in place of HELLO means for the stored LAN token. */
enum class HandshakeRefusal {
    /** Not a refusal, such as HELLO. */
    NONE,

    /** ERROR 14, or BYE auth-failed or token-rotated. The stored token is no longer valid. */
    TOKEN_REJECTED,

    /** ERROR 7 or BYE sessions-full, every session slot is taken. */
    BUSY,

    /** A BYE without an auth verdict. The sensor is unreachable for now and the token stays. */
    PEER_CLOSED,

    /** Any other ERROR. */
    INVALID,
}

/** Validated fields used by native storage and connection continuity. */
class SyncIdentity(val station: String, val device: String, val maxRead: Int, val boot: String? = null)
