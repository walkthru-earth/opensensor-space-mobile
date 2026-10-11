package earth.walkthru.aqsync.data.history.parquet

import java.io.EOFException
import java.io.File
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

actual typealias LocalFile = File

internal actual fun LocalFile.openReader(): ByteReader = LocalFileReader(this)

internal actual val LocalFile.displayName: String get() = name

/**
 * A [ByteReader] with positional reads, `FileChannel.read(dst, position)`, and no mapping. Each read
 * copies exactly its range into a new array. Concurrent reads are safe, a positional read never moves
 * the channel position. [close] closes the channel, so the descriptor is gone when it returns, not
 * when a collector runs (#61). [LocalRangeSource] reads through the same path.
 *
 * @throws FileNotFoundException when [file] is missing or is not a regular file, as the mapping did.
 */
class LocalFileReader(private val file: File) : ByteReader {
    private val channel: FileChannel = run {
        // Unix opens a directory read-only, so refuse it here, not at the first read.
        if (!file.isFile) throw FileNotFoundException("${file.path} is not a regular file")
        FileChannel.open(file.toPath(), StandardOpenOption.READ)
    }

    override val size: Long = channel.size()

    override fun read(offset: Long, length: Int): ByteArray {
        check(channel.isOpen) { "${file.name} is closed" }
        checkRange(offset, length, size)
        val out = ByteArray(length)
        val buffer = ByteBuffer.wrap(out)
        while (buffer.hasRemaining()) {
            val n = channel.read(buffer, offset + buffer.position())
            if (n < 0) throw EOFException("${file.name} ended at ${offset + buffer.position()} of $size bytes")
        }
        return out
    }

    override fun close() = channel.close()
}
