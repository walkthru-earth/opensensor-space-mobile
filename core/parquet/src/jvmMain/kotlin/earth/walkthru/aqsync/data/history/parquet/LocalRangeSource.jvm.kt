package earth.walkthru.aqsync.data.history.parquet

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

actual class LocalRangeSource actual constructor(private val file: LocalFile) : RangeSource {
    private val channel: FileChannel = FileChannel.open(file.toPath(), StandardOpenOption.READ)
    private val size: Long = channel.size()
    private val id = SourceId(file.absolutePath, size, "$size-${file.lastModified()}")

    override suspend fun id(): SourceId = id

    override suspend fun readTail(n: Int): ByteView {
        val length = minOf(n.coerceAtLeast(0).toLong(), size).toInt()
        return withContext(Dispatchers.IO) { read(size - length, length) }
    }

    override suspend fun readRanges(ranges: List<LongRange>): List<ByteView> = withContext(Dispatchers.IO) {
        ranges.map { r -> if (r.isEmpty()) ByteView.EMPTY else rangeLength(r).let { len -> checkRange(r.first, len, size); read(r.first, len) } }
    }

    private fun read(offset: Long, length: Int): ByteView {
        val out = ByteArray(length)
        val buffer = ByteBuffer.wrap(out)
        while (buffer.hasRemaining()) {
            val n = channel.read(buffer, offset + buffer.position())
            if (n < 0) throw EOFException("${file.name} ended at ${offset + buffer.position()} of $size bytes")
        }
        return ByteView(out)
    }

    override fun close() = channel.close()
}
