package earth.walkthru.aqsync.data.protocol

/**
 * Completion check from the firmware's `docs/shared/ble-sync-protocol.md`:
 *
 * "A file is complete only when the phone has `size` bytes, its own CRC-32 equals `OPENED.crc32`,
 * and the head/tail magic is `PAR1`. Anything else is discarded, never presented as data."
 *
 * This is the one place that rule lives. Platform adapters (JVM files in `jvmMain`, iOS file handles)
 * measure size, CRC-32 and the head/tail bytes and hand them to [verify].
 */
object ParquetVerify {
    /** `PAR1`, the Parquet head and tail magic. */
    val MAGIC: ByteArray = byteArrayOf(0x50, 0x41, 0x52, 0x31)

    /** Minimum plausible Parquet file: head magic + footer length + tail magic. */
    const val MIN_SIZE: Int = 12

    sealed interface Result {
        data object Ok : Result
        data class SizeMismatch(val expected: Long, val actual: Long) : Result
        data class CrcMismatch(val expected: Long, val actual: Long) : Result
        data object BadMagic : Result

        val ok: Boolean get() = this is Ok
        fun describe(): String = when (this) {
            Ok -> "verified"
            is SizeMismatch -> "size mismatch: expected $expected bytes, have $actual"
            is CrcMismatch -> "CRC-32 mismatch: expected ${hex32(expected)}, computed ${hex32(actual)}"
            BadMagic -> "not a Parquet file: PAR1 magic missing"
        }
    }

    /** CRC-32 (IEEE), same as `java.util.zip.CRC32` and the firmware's serial `crc32=` field. */
    fun crc32(bytes: ByteArray): Long = Crc32().also { it.update(bytes) }.value

    /** True when [bytes] is at least [MIN_SIZE] long and starts and ends with [MAGIC]. */
    fun hasMagic(bytes: ByteArray): Boolean {
        if (bytes.size < MIN_SIZE) return false
        return startsWithMagic(bytes) && endsWithMagic(bytes)
    }

    /** True when [head] starts with [MAGIC] and [tail] ends with it. For adapters that read only the ends. */
    fun hasMagic(head: ByteArray, tail: ByteArray): Boolean = startsWithMagic(head) && endsWithMagic(tail)

    fun verify(bytes: ByteArray, expectedSize: Long, expectedCrc32: Long): Result = verify(
        expectedSize = expectedSize,
        expectedCrc32 = expectedCrc32,
        actualSize = bytes.size.toLong(),
        actualCrc32 = { crc32(bytes) },
        hasMagic = { hasMagic(bytes) },
    )

    /**
     * The completion rule in order: size, then CRC-32, then magic. [actualCrc32] and [hasMagic] run only
     * when the earlier checks pass, so a short file is never hashed.
     */
    inline fun verify(
        expectedSize: Long,
        expectedCrc32: Long,
        actualSize: Long,
        actualCrc32: () -> Long,
        hasMagic: () -> Boolean,
    ): Result {
        if (actualSize != expectedSize) return Result.SizeMismatch(expectedSize, actualSize)
        val crc = actualCrc32()
        if (crc != expectedCrc32) return Result.CrcMismatch(expectedCrc32, crc)
        if (actualSize < MIN_SIZE || !hasMagic()) return Result.BadMagic
        return Result.Ok
    }

    private fun startsWithMagic(bytes: ByteArray): Boolean {
        if (bytes.size < MAGIC.size) return false
        for (i in MAGIC.indices) if (bytes[i] != MAGIC[i]) return false
        return true
    }

    private fun endsWithMagic(bytes: ByteArray): Boolean {
        if (bytes.size < MAGIC.size) return false
        val start = bytes.size - MAGIC.size
        for (i in MAGIC.indices) if (bytes[start + i] != MAGIC[i]) return false
        return true
    }

    private fun hex32(value: Long): String = (value and 0xFFFF_FFFFL).toString(16).padStart(8, '0')
}
