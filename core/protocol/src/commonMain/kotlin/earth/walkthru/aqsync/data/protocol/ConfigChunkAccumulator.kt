package earth.walkthru.aqsync.data.protocol

/** Reassembles the ordered BLE CONFIG slices before UTF-8 and JSON decoding. */
class ConfigChunkAccumulator {
    private var flags: Int? = null
    private var total: Int? = null
    private val bytes = ArrayList<Byte>()

    /** Returns the complete CONFIG or null while more contiguous slices are required. */
    fun add(chunk: Frame.ConfigChunk): Frame.Config? {
        if (chunk.total !in 2..480 || chunk.bytes.isEmpty() || chunk.offset != bytes.size ||
            bytes.size + chunk.bytes.size > chunk.total ||
            (flags != null && flags != chunk.flags) || (total != null && total != chunk.total)
        ) {
            throw IllegalArgumentException("CONFIG_CHUNK missing, overlapping, inconsistent, or oversized")
        }
        flags = chunk.flags
        total = chunk.total
        bytes.addAll(chunk.bytes.toList())
        if (bytes.size < chunk.total) return null
        val json = bytes.toByteArray().decodeToString(throwOnInvalidSequence = true)
        return Frame.Config(chunk.flags, json)
    }
}
