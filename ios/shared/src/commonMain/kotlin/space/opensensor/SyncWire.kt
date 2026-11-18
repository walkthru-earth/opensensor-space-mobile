package space.opensensor

import space.opensensor.data.ble.TransportKind
import space.opensensor.data.lan.LanHandshake
import space.opensensor.data.lan.LanTokenPolicy
import space.opensensor.data.protocol.DeviceGatt
import space.opensensor.data.protocol.DeviceInfo
import space.opensensor.data.protocol.Frame
import space.opensensor.data.protocol.FrameCodec
import space.opensensor.data.protocol.FrameType
import space.opensensor.data.protocol.LanProtocol
import space.opensensor.data.protocol.Opcode
import space.opensensor.data.protocol.ProtoGate
import space.opensensor.data.protocol.ProtocolError
import space.opensensor.data.protocol.Request
import space.opensensor.data.protocol.requireProto3
import space.opensensor.link.DisconnectCause
import space.opensensor.link.peerClosedCause
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * Small Swift facade around the canonical shared codec for the session itself: the token
 * request, STATUS keepalive, LAN handshake and envelope, and reply decoding. File requests
 * (LIST, OPEN, READ, CLOSE) are built only by the shared engine behind [SensorSync].
 * Frame bytes stay binary end to end.
 * The byte-array members are internal. iOS callers use the `NSData` extensions in
 * `iosMain/SyncWireData.kt`, which copy once with `memcpy` instead of building hex strings.
 */
class SyncWire {
    val controlUuid: String get() = DeviceGatt.CONTROL
    val responseUuid: String get() = DeviceGatt.RESPONSE
    internal fun tokenFrame(): ByteArray = Request.GetToken.encode()
    internal fun statusFrame(): ByteArray = Request.Status.encode()
    internal fun handshakeFrame(token: ByteArray): ByteArray = LanProtocol.handshake(token)
    internal fun envelopeFrame(body: ByteArray): ByteArray = LanProtocol.encode(body)

    fun identity(json: String): SyncIdentity? = runCatching {
        val info = DeviceInfo.parse(json)
        val thing = info.thing ?: error("missing thing")
        val device = info.dev ?: error("missing hardware identity")
        require(requireProto3(info) is ProtoGate.Accepted && Regex("[0-9a-fA-F]{12}").matches(device))
        require(isThingId(thing))
        // Android ReadWindow.kt:21 uses 4096 when max_read is absent. Never above the advertised value.
        val maxRead = info.maxRead ?: DEFAULT_MAX_READ
        require(maxRead > 0)
        SyncIdentity(thing, device.lowercase(), maxRead.coerceIn(1, MAX_READ_CAP), info.boot, info.dict, info.desc)
    }.getOrNull()

    /** INFO that parses but reports a `proto` below 3. The adapter closes with an update notice. */
    fun outdated(json: String): Boolean = runCatching { requireProto3(DeviceInfo.parse(json)) is ProtoGate.UpdateFirmware }.getOrDefault(false)

    /**
     * Whether this Bluetooth connection fetches the Wi-Fi token quietly, by the shared
     * [LanTokenPolicy] as Android does: once per connection, only when none is stored.
     */
    fun shouldFetchToken(json: String, hasToken: Boolean): Boolean = runCatching {
        tokenPolicy.shouldFetch(TransportKind.BLE, DeviceInfo.parse(json).proto, if (hasToken) STORED else null)
    }.getOrDefault(false)

    /**
     * The shared [LanHandshake] verdict on the frame that answered the handshake, against the saved
     * [thing] and [device]. ACCEPTED carries the identity [identity] validates, else BAD_INFO.
     */
    internal fun helloCheckFrame(bytes: ByteArray, thing: String, device: String): HelloCheck {
        val frame = runCatching { FrameCodec.decode(bytes) }.getOrNull() ?: return HelloCheck(HelloVerdict.WRONG_KIND)
        return when (val verdict = LanHandshake.verify(frame, savedDevice = device, savedThing = thing)) {
            is LanHandshake.Verdict.Accepted -> identity(verdict.hello.infoJson)
                ?.let { HelloCheck(HelloVerdict.ACCEPTED, it, verdict.hello.payloadMax, verdict.hello.infoJson) }
                ?: HelloCheck(HelloVerdict.BAD_INFO)
            LanHandshake.Verdict.WrongKind -> HelloCheck(HelloVerdict.WRONG_KIND)
            is LanHandshake.Verdict.OldProto -> HelloCheck(HelloVerdict.OLD_PROTO)
            is LanHandshake.Verdict.PayloadCapOutOfRange -> HelloCheck(HelloVerdict.PAYLOAD_CAP_OUT_OF_RANGE)
            LanHandshake.Verdict.BadInfo -> HelloCheck(HelloVerdict.BAD_INFO)
            LanHandshake.Verdict.DeviceChanged -> HelloCheck(HelloVerdict.DEVICE_CHANGED)
            LanHandshake.Verdict.ThingChanged -> HelloCheck(HelloVerdict.THING_CHANGED)
        }
    }

    companion object {
        /** LAN limits from the shared [LanHandshake], so Swift holds no literal. Seconds are `TimeInterval`. */
        val lanConnectTimeoutSeconds: Double = LanHandshake.CONNECT_TIMEOUT.seconds()
        val lanHelloTimeoutSeconds: Double = LanHandshake.HELLO_TIMEOUT.seconds()
        val lanKeepaliveSeconds: Double = LanHandshake.KEEPALIVE_AFTER_IDLE.seconds()
        val lanIdleSeconds: Double = LanHandshake.IDLE_BOUND.seconds()
        val lanPayloadMax: Int = LanHandshake.PAYLOAD_MAX

        /**
         * How often the iOS socket checks the keepalive and idle bound. Android's `LinkSession`
         * sleeps until the next due time instead. Short against the gap between the two bounds.
         */
        val lanLivenessCheckSeconds: Double = 15.0

        private fun Duration.seconds(): Double = toDouble(DurationUnit.SECONDS)

        private const val STORED = "stored"
        const val DEFAULT_MAX_READ = 4096
        private const val MAX_READ_CAP = 16384
        private val THING_ID = Regex("[0-9A-Za-z._-]+")

        /**
         * Same rule as Android (DeviceContinuityPolicy.kt:60, FileArchive.kt:131). The thing is one
         * archive directory, so a slash never passes and `.` or `..` alone is refused.
         */
        fun isThingId(thing: String): Boolean = THING_ID.matches(thing) && thing != "." && thing != ".."
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

/** What [frameKindOf] says about a frame body before any decode. */
enum class FrameKind {
    /** A STATUS push, small JSON the socket reads itself. */
    STATUS,

    /** A LIVE push, small JSON the socket reads itself. */
    LIVE,

    /** Any other known type. It goes to the shared link as is, which decodes and checks it once. */
    REPLY,

    /** Empty, or a type this app does not know. The socket closes, as before. */
    INVALID,
}

internal fun frameKindOf(type: Int?): FrameKind = when {
    type == null -> FrameKind.INVALID
    type == FrameType.STATUS -> FrameKind.STATUS
    type == FrameType.LIVE -> FrameKind.LIVE
    FrameType.isKnown(type) -> FrameKind.REPLY
    else -> FrameKind.INVALID
}

/** [LanHandshake.Verdict] flattened for Swift. */
enum class HelloVerdict { ACCEPTED, WRONG_KIND, OLD_PROTO, PAYLOAD_CAP_OUT_OF_RANGE, BAD_INFO, DEVICE_CHANGED, THING_CHANGED }

/** One HELLO check. [identity], [payloadMax] and [json] are set only when ACCEPTED. */
class HelloCheck(
    val verdict: HelloVerdict,
    val identity: SyncIdentity? = null,
    val payloadMax: Int = 0,
    val json: String = "",
)

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

/**
 * Validated fields used by native storage and connection continuity. [dict] and [desc] name the
 * board's column dictionary and the template it serves (#191), both unvalidated here.
 */
class SyncIdentity(
    val thing: String,
    val device: String,
    val maxRead: Int,
    val boot: String? = null,
    val dict: String? = null,
    val desc: String? = null,
)
