package earth.walkthru.aqsync.data.protocol

/**
 * Splits the LAN envelope (`len` u16 little-endian + `len` bytes, see [LanProtocol]) out of a byte
 * stream that arrives in arbitrary slices, as socket callbacks deliver it. Feed every received
 * slice to [append], then call [next] until it returns null.
 *
 * The length rule is the one `LanFraming.read` applies to a blocking stream: a `len` outside
 * `1..maxLen` throws [FrameDecodeException], because a zero-length frame has no type byte and an
 * oversize one means framing is lost. The caller drops the link then. [maxLen] starts at
 * [LanProtocol.PAYLOAD_MAX] and follows `HELLO.payload_max` once the handshake is done.
 */
class LanFrameSplitter(var maxLen: Int = LanProtocol.PAYLOAD_MAX) {
    private var buffer = ByteArray(INITIAL_CAPACITY)
    private var start = 0
    private var end = 0

    /** Bytes received but not yet returned as a frame. */
    val bufferedBytes: Int get() = end - start

    /** Adds `bytes[offset until offset + length]` to the stream. */
    fun append(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        checkRange(bytes.size, offset, length)
        if (length == 0) return
        if (buffer.size - end < length) makeRoom(length)
        bytes.copyInto(buffer, end, offset, offset + length)
        end += length
    }

    /** The next complete frame body, or null when more bytes are needed. */
    fun next(): ByteArray? {
        if (bufferedBytes < 2) return null
        val len = LanProtocol.frameLength(buffer[start].toInt(), buffer[start + 1].toInt(), maxLen)
        if (bufferedBytes < 2 + len) return null
        val body = buffer.copyOfRange(start + 2, start + 2 + len)
        start += 2 + len
        if (start == end) {
            start = 0
            end = 0
        }
        return body
    }

    /** Moves unread bytes to the front, and grows the buffer only when that is not enough. */
    private fun makeRoom(incoming: Int) {
        val pending = bufferedBytes
        val needed = pending + incoming
        val target = if (needed <= buffer.size) buffer else ByteArray(maxOf(needed, buffer.size * 2))
        buffer.copyInto(target, 0, start, end)
        buffer = target
        start = 0
        end = pending
    }

    private companion object {
        /** One full frame plus its length prefix at the default payload_max. */
        const val INITIAL_CAPACITY = LanProtocol.PAYLOAD_MAX + 2
    }
}
