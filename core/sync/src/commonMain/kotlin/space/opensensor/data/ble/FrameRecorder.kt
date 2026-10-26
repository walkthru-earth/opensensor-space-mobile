package space.opensensor.data.ble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import space.opensensor.data.protocol.FrameType
import space.opensensor.data.protocol.Opcode
import space.opensensor.sync.SyncLock
import space.opensensor.sync.nowEpochMs
import space.opensensor.sync.monotonicMs as platformMonotonicMs

/** Which way a raw frame travelled. */
enum class FrameDirection { TX, RX }

/**
 * One raw frame as seen at the transport boundary, for the Advanced → Debug screen. Only the first
 * [RawFrameRecord.PREVIEW_BYTES] bytes are kept, and a secret frame ([FrameRedaction.isSecret]) is
 * recorded with its body redacted and [length] 0: the LAN token and the Wi-Fi passphrase are secrets.
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
        val secret = FrameRedaction.isSecret(direction, bytes)
        // A secret frame records length 0, so the passphrase or PIN length cannot be read from it.
        val length = if (secret) 0 else bytes.size
        val rec = RawFrameRecord(nowEpochMs(), direction, type, length, FrameRedaction.preview(direction, bytes), secret)
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

    companion object {
        const val DEFAULT_CAPACITY = 200
        private const val SNAPSHOT_INTERVAL_MS = 100L
    }
}
