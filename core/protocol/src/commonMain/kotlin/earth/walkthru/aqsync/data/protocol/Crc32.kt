package earth.walkthru.aqsync.data.protocol

/**
 * Streaming CRC-32 (IEEE 802.3, reflected polynomial 0xEDB88320), the value the firmware reports
 * as `OPENED.crc32` and as the serial `crc32=` field.
 *
 * The JVM actual delegates to `java.util.zip.CRC32`, which the runtime accelerates. Other targets
 * use [PortableCrc32]. Both produce identical values, checked in `commonTest`.
 */
expect class Crc32() {
    /** Adds `bytes[offset until offset + length]` to the running checksum. */
    fun update(bytes: ByteArray, offset: Int, length: Int)

    /** Adds every byte of [bytes]. */
    fun update(bytes: ByteArray)

    /** Starts over, as if no bytes had been added. */
    fun reset()

    /** The checksum of every byte added so far, as an unsigned 32-bit value. */
    val value: Long
}

/** Pure-Kotlin, table-driven CRC-32. Same results as [Crc32] on every target. */
class PortableCrc32 {
    private var crc: Int = INITIAL

    fun update(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        checkRange(bytes.size, offset, length)
        var c = crc
        for (i in offset until offset + length) {
            c = TABLE[(c xor bytes[i].toInt()) and 0xFF] xor (c ushr 8)
        }
        crc = c
    }

    fun reset() {
        crc = INITIAL
    }

    val value: Long get() = (crc xor INITIAL).toLong() and 0xFFFF_FFFFL

    private companion object {
        const val INITIAL: Int = -1 // 0xFFFFFFFF
        const val POLYNOMIAL: Int = 0xEDB88320.toInt()

        val TABLE: IntArray = IntArray(256) { n ->
            var c = n
            repeat(8) { c = if (c and 1 != 0) POLYNOMIAL xor (c ushr 1) else c ushr 1 }
            c
        }
    }
}

internal fun checkRange(size: Int, offset: Int, length: Int) {
    if (offset < 0 || length < 0 || offset > size - length) {
        throw IndexOutOfBoundsException("offset $offset, length $length, size $size")
    }
}
