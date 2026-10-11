package earth.walkthru.aqsync.data.history.parquet

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/** Positional reads through one [LocalFileReader], whose reference counted descriptor makes [close] safe while reads run. */
actual class LocalRangeSource actual constructor(file: LocalFile) : RangeSource {
    private val reader = LocalFileReader(file.path)
    private val size: Long = reader.size
    private val id = SourceId(file.path, size, "$size-${reader.modifiedNs}")

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
