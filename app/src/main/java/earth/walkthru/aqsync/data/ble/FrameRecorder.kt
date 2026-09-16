package earth.walkthru.aqsync.data.ble

import earth.walkthru.aqsync.data.protocol.FrameType
import earth.walkthru.aqsync.data.protocol.Opcode
import earth.walkthru.aqsync.data.protocol.toHex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Which way a raw frame travelled. */
enum class FrameDirection { TX, RX }

/**
 * One raw frame as seen at the transport boundary, for the Advanced → Debug screen. Only the first
 * [RawFrameRecord.PREVIEW_BYTES] bytes are kept, and a `TOKEN` frame or `SET_CONFIG` request is
 * recorded with its body redacted: the LAN token and the Wi-Fi passphrase are secrets.
 */
data class RawFrameRecord(
    val atEpochMs: Long,
    val direction: FrameDirection,
    /** Frame type (RX) or opcode (TX). */
    val type: Int,
    val length: Int,
    val previewHex: String,
    val redacted: Boolean,
) {
    val typeName: String get() = if (direction == FrameDirection.TX) Opcode.name(type) else FrameType.name(type)

    companion object {
        const val PREVIEW_BYTES = 16
    }
}

/** Fixed-size ring of the most recent frames. Cheap enough to leave on; the UI only reads it in Advanced mode. */
class FrameRecorder(private val capacity: Int = DEFAULT_CAPACITY) {
    private val ring = ArrayDeque<RawFrameRecord>(capacity)
    private val lock = Any()
    private val _records = MutableStateFlow<List<RawFrameRecord>>(emptyList())
    val records: StateFlow<List<RawFrameRecord>> = _records

    fun tx(request: ByteArray) = record(FrameDirection.TX, request)
    fun rx(frame: ByteArray) = record(FrameDirection.RX, frame)

    private fun record(direction: FrameDirection, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val type = bytes[0].toInt() and 0xFF
        val secret = (direction == FrameDirection.RX && type == FrameType.TOKEN) ||
            (direction == FrameDirection.TX && type == Opcode.SET_CONFIG)
        val preview = if (secret) bytes.copyOf(1).toHex() + "…" else bytes.copyOf(minOf(bytes.size, RawFrameRecord.PREVIEW_BYTES)).toHex()
        val rec = RawFrameRecord(System.currentTimeMillis(), direction, type, bytes.size, preview, secret)
        val snapshot: List<RawFrameRecord>
        synchronized(lock) {
            if (ring.size >= capacity) ring.removeFirst()
            ring.addLast(rec)
            snapshot = ring.toList()
        }
        _records.value = snapshot
    }

    fun clear() {
        synchronized(lock) { ring.clear() }
        _records.value = emptyList()
    }

    companion object {
        const val DEFAULT_CAPACITY = 200
    }
}
