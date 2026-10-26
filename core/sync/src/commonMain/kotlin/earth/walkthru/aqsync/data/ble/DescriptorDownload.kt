package earth.walkthru.aqsync.data.ble

import earth.walkthru.aqsync.data.protocol.Frame
import earth.walkthru.aqsync.link.LinkTimeoutException

/** The board template download broke the `GET_DESCRIPTOR` rules and was given up. */
class DescriptorDownloadException(message: String) : Exception(message)

/**
 * Reassembles the board template from `DESCRIPTOR_CHUNK` replies (#191). Each request asks for the
 * next missing offset, so a clamped chunk simply moves the cursor less far. A chunk for an offset
 * the phone did not ask for is dropped and the same offset is asked again. A lost reply costs one
 * more request for the same offset. When `total` changes the download restarts once from offset
 * 0, and a second change fails it.
 */
object DescriptorDownload {
    /** `DESCRIPTOR_CHUNK` header: type, total u32, offset u32. */
    const val HEADER_BYTES = 9

    /** Largest template the phone accepts. Real templates are a few KiB. */
    const val MAX_BYTES = 256 * 1024

    /** Requests for one offset that may come back foreign or lost before the download fails. */
    const val RETRIES_PER_OFFSET = 3

    /** The bytes one chunk can carry on a link with this `payload_max`. */
    fun chunkLength(payloadMax: Int): Int = (payloadMax - HEADER_BYTES).coerceIn(1, 0xFFFF)

    suspend fun fetch(chunkLength: Int, chunk: suspend (offset: Long, length: Int) -> Frame.DescriptorChunk): ByteArray {
        require(chunkLength in 1..0xFFFF) { "chunk length $chunkLength out of range" }
        var total = -1L
        var restarted = false
        var buffer = ByteArray(0)
        var filled = 0L
        var misses = 0
        while (total < 0 || filled < total) {
            val reply = try {
                chunk(filled, chunkLength)
            } catch (e: LinkTimeoutException) {
                if (++misses > RETRIES_PER_OFFSET) throw e
                continue
            }
            if (reply.offset != filled) {
                if (++misses > RETRIES_PER_OFFSET) throw DescriptorDownloadException("no chunk for offset $filled")
                continue
            }
            if (reply.total != total) {
                if (reply.total !in 1..MAX_BYTES.toLong()) throw DescriptorDownloadException("template size ${reply.total} out of range")
                if (total >= 0) {
                    if (restarted) throw DescriptorDownloadException("template changed twice during the download")
                    restarted = true
                    total = reply.total
                    buffer = ByteArray(total.toInt())
                    filled = 0
                    misses = 0
                    continue
                }
                total = reply.total
                buffer = ByteArray(total.toInt())
            }
            val n = reply.bytes.size
            if (n == 0 || filled + n > total) throw DescriptorDownloadException("chunk of $n bytes at $filled overruns $total")
            reply.bytes.copyInto(buffer, filled.toInt())
            filled += n
            misses = 0
        }
        return buffer
    }
}
