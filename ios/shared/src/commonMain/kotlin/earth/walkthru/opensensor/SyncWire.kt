package earth.walkthru.opensensor

import earth.walkthru.aqsync.data.protocol.AqGatt
import earth.walkthru.aqsync.data.protocol.DeviceInfo
import earth.walkthru.aqsync.data.protocol.Frame
import earth.walkthru.aqsync.data.protocol.FrameCodec
import earth.walkthru.aqsync.data.protocol.LanProtocol
import earth.walkthru.aqsync.data.protocol.Request

/** Small Swift facade around the canonical shared codec. Hex avoids exposing Kotlin byte arrays. */
class SyncWire {
    val controlUuid: String get() = AqGatt.CONTROL
    val responseUuid: String get() = AqGatt.RESPONSE
    fun tokenRequest(): String = Request.GetToken.encode().hex()
    fun statusRequest(): String = Request.Status.encode().hex()
    fun listRequest(): String = Request.List.encode().hex()
    fun openRequest(name: String): String = Request.Open(name).encode().hex()
    fun readRequest(handle: Int, offset: Long, length: Long): String = Request.Read(handle, offset, length).encode().hex()
    fun closeRequest(handle: Int): String = Request.Close(handle).encode().hex()
    fun handshake(tokenHex: String): String = LanProtocol.handshake(tokenHex.bytes()).hex()
    fun envelope(bodyHex: String): String = LanProtocol.encode(bodyHex.bytes()).hex()

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
    fun decode(hex: String): SyncReply? = runCatching {
        when (val frame = FrameCodec.decode(hex.bytes())) {
            is Frame.File -> SyncReply("file", name = frame.name, size = frame.bytes)
            is Frame.ListEnd -> SyncReply("listEnd", count = frame.count)
            is Frame.Opened -> SyncReply("opened", name = frame.name, size = frame.size, crc = frame.crc32, handle = frame.handle)
            is Frame.Chunk -> SyncReply("chunk", handle = frame.handle, offset = frame.offset, dataHex = frame.payload.hex())
            is Frame.ReadEnd -> SyncReply("readEnd", handle = frame.handle, offset = frame.nextOffset, code = frame.status)
            is Frame.Closed -> SyncReply("closed", handle = frame.handle)
            is Frame.Token -> SyncReply("token", port = frame.port, dataHex = frame.token.hex())
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
    val dataHex: String = "",
    val json: String = "",
) {
    override fun toString(): String = "SyncReply($kind)"
}

private fun ByteArray.hex(): String = joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
private fun String.bytes(): ByteArray {
    require(length % 2 == 0)
    return ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

/** Validated fields used by native storage and connection continuity. */
class SyncIdentity(val station: String, val device: String, val maxRead: Int)
