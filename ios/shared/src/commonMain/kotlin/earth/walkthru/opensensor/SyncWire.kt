package earth.walkthru.opensensor

import earth.walkthru.aqsync.data.protocol.AqGatt
import earth.walkthru.aqsync.data.protocol.DeviceInfo
import earth.walkthru.aqsync.data.protocol.Frame
import earth.walkthru.aqsync.data.protocol.FrameCodec
import earth.walkthru.aqsync.data.protocol.LanProtocol
import earth.walkthru.aqsync.data.protocol.Request

/**
 * Small Swift facade around the canonical shared codec. Frame bytes stay binary end to end.
 * The byte-array members are internal. iOS callers use the `NSData` extensions in
 * `iosMain/SyncWireData.kt`, which copy once with `memcpy` instead of building hex strings.
 */
class SyncWire {
    val controlUuid: String get() = AqGatt.CONTROL
    val responseUuid: String get() = AqGatt.RESPONSE
    internal fun tokenFrame(): ByteArray = Request.GetToken.encode()
    internal fun statusFrame(): ByteArray = Request.Status.encode()
    internal fun listFrame(): ByteArray = Request.List.encode()
    internal fun openFrame(name: String): ByteArray = Request.Open(name).encode()
    internal fun readFrame(handle: Int, offset: Long, length: Long): ByteArray =
        Request.Read(handle, offset, length).encode()
    internal fun closeFrame(handle: Int): ByteArray = Request.Close(handle).encode()
    internal fun handshakeFrame(token: ByteArray): ByteArray = LanProtocol.handshake(token)
    internal fun envelopeFrame(body: ByteArray): ByteArray = LanProtocol.encode(body)

    fun identity(json: String): SyncIdentity? = runCatching {
        val info = DeviceInfo.parse(json)
        val station = info.station ?: error("missing station")
        val device = info.dev ?: error("missing hardware identity")
        require(info.proto == 2 && Regex("[0-9a-fA-F]{12}").matches(device))
        require(Regex("[A-Za-z0-9_-]+").matches(station))
        require((info.maxRead ?: 16384) > 0)
        SyncIdentity(station, device.lowercase(), (info.maxRead ?: 16384).coerceIn(1, 16384))
    }.getOrNull()

    /** Malformed frames return null; no secret bytes are included in errors or descriptions. */
    internal fun decodeFrame(bytes: ByteArray): SyncReply? = runCatching {
        when (val frame = FrameCodec.decode(bytes)) {
            is Frame.File -> SyncReply("file", name = frame.name, size = frame.bytes)
            is Frame.ListEnd -> SyncReply("listEnd", count = frame.count)
            is Frame.Opened -> SyncReply("opened", name = frame.name, size = frame.size, crc = frame.crc32, handle = frame.handle)
            is Frame.Chunk -> SyncReply("chunk", handle = frame.handle, offset = frame.offset, bytes = frame.payload)
            is Frame.ReadEnd -> SyncReply("readEnd", handle = frame.handle, offset = frame.nextOffset, code = frame.status)
            is Frame.Closed -> SyncReply("closed", handle = frame.handle)
            is Frame.Token -> SyncReply("token", port = frame.port, bytes = frame.token)
            is Frame.Hello -> SyncReply("hello", code = frame.proto, count = frame.payloadMax, json = frame.infoJson)
            is Frame.LivePush -> SyncReply("live", json = frame.json)
            is Frame.StatusPush -> SyncReply("status", json = frame.json)
            is Frame.Error -> SyncReply("error", code = frame.code)
            else -> null
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
    val json: String = "",
    internal val bytes: ByteArray = EMPTY,
) {
    override fun toString(): String = "SyncReply($kind)"

    private companion object {
        val EMPTY = ByteArray(0)
    }
}

/** Validated fields used by native storage and connection continuity. */
class SyncIdentity(val station: String, val device: String, val maxRead: Int)
