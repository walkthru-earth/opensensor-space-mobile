package earth.walkthru.aqsync.data.history.parquet

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

actual typealias LocalFile = File

internal actual fun LocalFile.mapReadOnly(): ByteReader = MappedFileReader(this)

internal actual val LocalFile.displayName: String get() = name

/**
 * A [ByteReader] over `FileChannel.map(READ_ONLY)`. Reads go through a duplicate of the mapped buffer,
 * so concurrent readers never share a position. Files above 2 GiB are refused (the mapping API limit).
 *
 * [close] drops the reader's only reference to the mapping. Neither the JVM nor Android has a public
 * unmap call, so the address range is returned when the buffer is collected, which [close] makes
 * possible right away instead of when this reader is.
 */
class MappedFileReader(file: File) : ByteReader {
    @Volatile
    private var mapped: ByteBuffer? = RandomAccessFile(file, "r").use { raf ->
        raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
    }

    override val size: Long = mapped!!.capacity().toLong()

    /** Whether [close] released the mapping. */
    val isClosed: Boolean get() = mapped == null

    override fun read(offset: Long, length: Int): ByteArray {
        val buffer = checkNotNull(mapped) { "reader is closed" }
        checkRange(offset, length, size)
        val out = ByteArray(length)
        val view = buffer.duplicate()
        view.position(offset.toInt())
        view.get(out)
        return out
    }

    override fun close() {
        mapped = null
    }
}
