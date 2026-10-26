package space.opensensor.data.history.parquet

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual class LocalRangeSource actual constructor(file: LocalFile) : RangeSource {
    private val reader = LocalFileReader(file)
    private val size: Long = reader.size
    private val id = SourceId(file.absolutePath, size, "$size-${file.lastModified()}")

    override suspend fun id(): SourceId = id

    override suspend fun readTail(n: Int): ByteView {
        val length = minOf(n.coerceAtLeast(0).toLong(), size).toInt()
        return withContext(Dispatchers.IO) { ByteView(reader.read(size - length, length)) }
    }

    override suspend fun readRanges(ranges: List<LongRange>): List<ByteView> = withContext(Dispatchers.IO) {
        ranges.map { r -> if (r.isEmpty()) ByteView.EMPTY else ByteView(reader.read(r.first, rangeLength(r))) }
    }

    override fun close() = reader.close()
}
