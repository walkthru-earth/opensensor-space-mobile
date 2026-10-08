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
 */
class MappedFileReader(file: File) : ByteReader {
    private val mapped: ByteBuffer = RandomAccessFile(file, "r").use { raf ->
        raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
    }

    override val size: Long = mapped.capacity().toLong()

    override fun read(offset: Long, length: Int): ByteArray {
        checkRange(offset, length, size)
        val out = ByteArray(length)
        val view = mapped.duplicate()
        view.position(offset.toInt())
        view.get(out)
        return out
    }
}
