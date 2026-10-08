package earth.walkthru.aqsync.data.ble

import earth.walkthru.aqsync.data.protocol.FrameType
import earth.walkthru.aqsync.data.protocol.Opcode
import earth.walkthru.aqsync.sync.SyncLock
import earth.walkthru.aqsync.sync.nowEpochMs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import earth.walkthru.aqsync.sync.monotonicMs as platformMonotonicMs

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

/**
 * Fixed-size ring of the most recent frames. Every frame is retained, but CHUNK bursts publish at
 * most ten snapshots per second; control/terminal frames publish immediately, including READ_END.
 */
class FrameRecorder(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val monotonicMs: () -> Long = ::platformMonotonicMs,
) {
    private val ring = ArrayDeque<RawFrameRecord>(capacity)
    private val lock = SyncLock()
    private val _records = MutableStateFlow<List<RawFrameRecord>>(emptyList())
    val records: StateFlow<List<RawFrameRecord>> = _records
    private var lastPublishedAt: Long? = null
    private var unpublished = false

    init {
        require(capacity > 0)
    }

    fun tx(request: ByteArray) = record(FrameDirection.TX, request)
    fun rx(frame: ByteArray) = record(FrameDirection.RX, frame)

    private fun record(direction: FrameDirection, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val type = bytes[0].toInt() and 0xFF
        val secret = (direction == FrameDirection.RX && type == FrameType.TOKEN) ||
            (direction == FrameDirection.TX && type == Opcode.SET_CONFIG)
        val preview = previewHex(bytes, if (secret) 1 else minOf(bytes.size, RawFrameRecord.PREVIEW_BYTES)) + if (secret) "…" else ""
        val rec = RawFrameRecord(nowEpochMs(), direction, type, bytes.size, preview, secret)
        lock.withLock {
            if (ring.size >= capacity) ring.removeFirst()
            ring.addLast(rec)
            unpublished = true
            val now = monotonicMs()
            val last = lastPublishedAt
            if (direction == FrameDirection.RX && type == FrameType.CHUNK && last != null && now - last < SNAPSHOT_INTERVAL_MS) return@withLock
            // Publish under the same lock so concurrent TX/RX cannot overwrite a newer snapshot.
            _records.value = ring.toList()
            lastPublishedAt = now
            unpublished = false
        }
    }

    /** A cancelled or failed request may finish before the next CHUNK snapshot or READ_END. */
    fun flush() {
        lock.withLock {
            if (!unpublished) return@withLock
            _records.value = ring.toList()
            lastPublishedAt = monotonicMs()
            unpublished = false
        }
    }

    fun clear() {
        lock.withLock {
            ring.clear()
            lastPublishedAt = null
            unpublished = false
            _records.value = emptyList()
        }
    }

    /** Avoid copying the prefix or constructing a String for each preview byte. */
    private fun previewHex(bytes: ByteArray, count: Int): String = CharArray(count * 2) { index ->
        val byte = bytes[index / 2].toInt() and 0xFF
        HEX[if (index % 2 == 0) byte ushr 4 else byte and 0x0F]
    }.concatToString()

    companion object {
        const val DEFAULT_CAPACITY = 200
        private const val SNAPSHOT_INTERVAL_MS = 100L
        private const val HEX = "0123456789abcdef"
    }
}
