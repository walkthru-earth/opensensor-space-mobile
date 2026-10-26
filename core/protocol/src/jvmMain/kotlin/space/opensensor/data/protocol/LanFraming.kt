package space.opensensor.data.protocol

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * The LAN envelope (protocol v2, "LAN transport"): every message in both directions is `len` u16
 * little-endian followed by exactly `len` bytes. Phone → device carries a control request body
 * (≤ 512 bytes); device → phone carries one response or push frame (≤ `payload_max` = 1024).
 *
 * The handshake is the one unframed message: `"OSS1"` + 32-byte token, sent by the phone within
 * 5 s of connecting.
 */
object LanFraming {
    /** DNS-SD service type; the instance name is the BLE local name (`OS-xxxx`). */
    const val SERVICE_TYPE = LanProtocol.SERVICE_TYPE
    const val SERVICE_TYPE_DOT = LanProtocol.SERVICE_TYPE_DOT
    const val DEFAULT_PORT = LanProtocol.DEFAULT_PORT
    const val HANDSHAKE_MAGIC = LanProtocol.HANDSHAKE_MAGIC
    const val HANDSHAKE_TIMEOUT_MS = LanProtocol.HANDSHAKE_TIMEOUT_MS

    /** Largest device → phone frame; also what the transport reports as `mtu − 3`. */
    const val PAYLOAD_MAX = LanProtocol.PAYLOAD_MAX
    const val REQUEST_MAX = LanProtocol.REQUEST_MAX

    /** Idle limit on the device side; the phone pings (`STATUS`, 0x07) well before it. */
    const val DEVICE_IDLE_LIMIT_MS = LanProtocol.DEVICE_IDLE_LIMIT_MS
    const val KEEPALIVE_AFTER_IDLE_MS = LanProtocol.KEEPALIVE_AFTER_IDLE_MS

    /** TXT keys advertised with the service. */
    const val TXT_PROTO = LanProtocol.TXT_PROTO
    const val TXT_STATION = LanProtocol.TXT_STATION
    const val TXT_DEV = LanProtocol.TXT_DEV
    const val TXT_FW = LanProtocol.TXT_FW

    fun handshake(token: ByteArray): ByteArray = LanProtocol.handshake(token)

    fun encode(body: ByteArray): ByteArray = LanProtocol.encode(body)

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
        val len = LanProtocol.frameLength(b0, b1, maxLen)
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
