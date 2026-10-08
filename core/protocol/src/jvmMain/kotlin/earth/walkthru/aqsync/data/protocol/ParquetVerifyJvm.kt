package earth.walkthru.aqsync.data.protocol

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile

// JVM file and stream adapters for the shared completion rule in commonMain's ParquetVerify.
// Callers outside this package import them by name, for example
// `import earth.walkthru.aqsync.data.protocol.verify`.

/** CRC-32 of everything left in [stream]. The stream is not closed. */
fun ParquetVerify.crc32(stream: InputStream): Long {
    val crc = Crc32()
    val buf = ByteArray(64 * 1024)
    while (true) {
        val n = stream.read(buf)
        if (n < 0) break
        crc.update(buf, 0, n)
    }
    return crc.value
}

/** Checks the size first, streams [file] once for the CRC-32, then peeks the head and tail for `PAR1`. */
fun ParquetVerify.verify(file: File, expectedSize: Long, expectedCrc32: Long): ParquetVerify.Result {
    val len = file.length()
    return verify(
        expectedSize = expectedSize,
        expectedCrc32 = expectedCrc32,
        actualSize = len,
        actualCrc32 = { file.inputStream().buffered().use { crc32(it) } },
        hasMagic = {
            val head = ByteArray(MAGIC.size)
            val tail = ByteArray(MAGIC.size)
            RandomAccessFile(file, "r").use { raf ->
                raf.readFully(head)
                raf.seek(len - MAGIC.size)
                raf.readFully(tail)
            }
            hasMagic(head, tail)
        },
    )
}

/** SHA-256 of [file] as 64 lowercase hex characters, streamed in 64 KiB slices through [Sha256]. */
fun ParquetVerify.sha256Hex(file: File): String {
    val sha = Sha256()
    file.inputStream().buffered().use { input ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            sha.update(buf, 0, n)
        }
    }
    return sha.digest().toHex()
}
