package earth.walkthru.aqsync.data.protocol

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * The LAN envelope (protocol v2, "LAN transport"): every message in both directions is `len` u16
 * little-endian followed by exactly `len` bytes. Phone → device carries a control request body
 * (≤ 512 bytes); device → phone carries one response or push frame (≤ `payload_max` = 1024).
 *
 * The handshake is the one unframed message: `"AQS1"` + 32-byte token, sent by the phone within
 * 5 s of connecting.
 */
object LanFraming {
    /** DNS-SD service type; the instance name is the BLE local name (`AQ-xxxx`). */
    const val SERVICE_TYPE = "_aqsync._tcp"
    const val SERVICE_TYPE_DOT = "_aqsync._tcp."
    const val DEFAULT_PORT = 47390
    const val HANDSHAKE_MAGIC = "AQS1"
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
        return HANDSHAKE_MAGIC.toByteArray(Charsets.US_ASCII) + token
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

    fun write(out: OutputStream, body: ByteArray) {
        out.write(encode(body))
        out.flush()
    }

    /**
     * Read exactly one framed message. Returns null on a clean EOF *before* a frame starts; a
     * stream that ends mid-frame is an error (the peer vanished while writing), as is a `len`
     * outside `1..maxLen` — a zero-length frame has no type byte and cannot be dispatched, and an
     * oversize one means we have lost framing, so the only safe reaction is to drop the link.
     */
    fun read(input: InputStream, maxLen: Int = PAYLOAD_MAX): ByteArray? {
        val b0 = input.read()
        if (b0 < 0) return null
        val b1 = input.read()
        if (b1 < 0) throw EOFException("stream ended inside a frame length")
        val len = (b0 and 0xFF) or ((b1 and 0xFF) shl 8)
        if (len == 0) throw FrameDecodeException("zero-length LAN frame")
        if (len > maxLen) throw FrameDecodeException("LAN frame of $len bytes exceeds payload_max $maxLen")
        val body = ByteArray(len)
        var got = 0
        while (got < len) {
            val n = input.read(body, got, len - got)
            if (n < 0) throw EOFException("stream ended after $got of $len frame bytes")
            got += n
        }
        return body
    }

    /** Parse a whole captured stream into its frames (tests, debugging). */
    fun readAll(input: InputStream, maxLen: Int = PAYLOAD_MAX): List<ByteArray> {
        val frames = ArrayList<ByteArray>()
        while (true) {
            frames += read(input, maxLen) ?: return frames
        }
    }
}
