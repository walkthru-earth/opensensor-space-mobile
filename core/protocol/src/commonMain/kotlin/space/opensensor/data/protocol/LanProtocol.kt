package space.opensensor.data.protocol

/**
 * The LAN envelope (protocol v2, "LAN transport"): every message in both directions is `len` u16
 * little-endian followed by exactly `len` bytes. Phone → device carries a control request body
 * (≤ 512 bytes); device → phone carries one response or push frame (≤ `payload_max` = 1024).
 *
 * The handshake is the one unframed message: `"OSS1"` + 32-byte token, sent by the phone within
 * 5 s of connecting.
 */
object LanProtocol {
    /** DNS-SD service type; the instance name is the BLE local name (`OS-xxxx`). */
    const val SERVICE_TYPE = "_ossync._tcp"
    const val SERVICE_TYPE_DOT = "_ossync._tcp."
    const val DEFAULT_PORT = 47390
    const val HANDSHAKE_MAGIC = "OSS1"
    const val HANDSHAKE_TIMEOUT_MS = 5_000L

    /** Largest device → phone frame; also what the transport reports as `mtu − 3`. */
    const val PAYLOAD_MAX = 1024
    const val REQUEST_MAX = 512

    /** Idle limit on the device side; the phone pings (`STATUS`, 0x07) well before it. */
    const val DEVICE_IDLE_LIMIT_MS = 300_000L
    const val KEEPALIVE_AFTER_IDLE_MS = 120_000L

    /** TXT keys advertised with the service. */
    const val TXT_PROTO = "proto"
    const val TXT_STATION = "station"
    const val TXT_DEV = "dev"
    const val TXT_FW = "fw"

    fun handshake(token: ByteArray): ByteArray {
        require(token.size == LAN_TOKEN_BYTES) { "token must be $LAN_TOKEN_BYTES bytes" }
        return HANDSHAKE_MAGIC.encodeToByteArray() + token
    }

    /**
     * The body length from the two prefix bytes [lo] and [hi], checked against `1..maxLen`. A
     * zero-length frame has no type byte to dispatch on, and an oversize one means framing is lost,
     * so both throw [FrameDecodeException] and the caller drops the link.
     */
    fun frameLength(lo: Int, hi: Int, maxLen: Int): Int {
        val len = (lo and 0xFF) or ((hi and 0xFF) shl 8)
        if (len == 0) throw FrameDecodeException("zero-length LAN frame")
        if (len > maxLen) throw FrameDecodeException("LAN frame of $len bytes exceeds payload_max $maxLen")
        return len
    }

    /** `len` u16 LE + body. */
    fun encode(body: ByteArray): ByteArray {
        require(body.size in 1..0xFFFF) { "frame body must be 1..65535 bytes, was ${body.size}" }
        val out = ByteArray(2 + body.size)
        out[0] = (body.size and 0xFF).toByte()
        out[1] = ((body.size ushr 8) and 0xFF).toByte()
        body.copyInto(out, 2)
        return out
    }
}
