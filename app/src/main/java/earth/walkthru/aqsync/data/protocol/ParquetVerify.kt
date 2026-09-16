package earth.walkthru.aqsync.data.protocol

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.CRC32

/**
 * Completion check from docs/ble-sync-protocol.md:
 *
 * "A file is complete only when the phone has `size` bytes, its own CRC-32 equals `OPENED.crc32`,
 * and the head/tail magic is `PAR1`. Anything else is discarded, never presented as data."
 */
object ParquetVerify {
    val MAGIC: ByteArray = "PAR1".toByteArray(Charsets.US_ASCII)

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
            is CrcMismatch -> "CRC-32 mismatch: expected %08x, computed %08x".format(expected, actual)
            BadMagic -> "not a Parquet file: PAR1 magic missing"
        }
    }

    /** CRC-32 (IEEE), same as `java.util.zip.CRC32` and the firmware's serial `crc32=` field. */
    fun crc32(bytes: ByteArray): Long = CRC32().also { it.update(bytes) }.value

    fun crc32(stream: InputStream): Long {
        val crc = CRC32()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = stream.read(buf)
            if (n < 0) break
            crc.update(buf, 0, n)
        }
        return crc.value
    }

    fun hasMagic(bytes: ByteArray): Boolean {
        if (bytes.size < MIN_SIZE) return false
        for (i in MAGIC.indices) {
            if (bytes[i] != MAGIC[i]) return false
            if (bytes[bytes.size - MAGIC.size + i] != MAGIC[i]) return false
        }
        return true
    }

    fun verify(bytes: ByteArray, expectedSize: Long, expectedCrc32: Long): Result {
        if (bytes.size.toLong() != expectedSize) return Result.SizeMismatch(expectedSize, bytes.size.toLong())
        val crc = crc32(bytes)
        if (crc != expectedCrc32) return Result.CrcMismatch(expectedCrc32, crc)
        if (!hasMagic(bytes)) return Result.BadMagic
        return Result.Ok
    }

    /** Streams [file] once for CRC, then peeks head/tail for the magic. */
    fun verify(file: File, expectedSize: Long, expectedCrc32: Long): Result {
        val len = file.length()
        if (len != expectedSize) return Result.SizeMismatch(expectedSize, len)
        val crc = file.inputStream().buffered().use { crc32(it) }
        if (crc != expectedCrc32) return Result.CrcMismatch(expectedCrc32, crc)
        if (len < MIN_SIZE) return Result.BadMagic
        val head = ByteArray(MAGIC.size)
        val tail = ByteArray(MAGIC.size)
        java.io.RandomAccessFile(file, "r").use { raf ->
            raf.readFully(head)
            raf.seek(len - MAGIC.size)
            raf.readFully(tail)
        }
        if (!head.contentEquals(MAGIC) || !tail.contentEquals(MAGIC)) return Result.BadMagic
        return Result.Ok
    }

    fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().toHex()
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
}
