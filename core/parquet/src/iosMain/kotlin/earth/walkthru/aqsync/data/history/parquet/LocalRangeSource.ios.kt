@file:OptIn(ExperimentalForeignApi::class)

package earth.walkthru.aqsync.data.history.parquet

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.posix.EINTR
import platform.posix.O_RDONLY
import platform.posix.errno
import platform.posix.fstat
import platform.posix.open
import platform.posix.pread
import platform.posix.stat
import platform.posix.strerror
import kotlin.concurrent.AtomicInt

actual class LocalRangeSource actual constructor(private val file: LocalFile) : RangeSource {
    private val fd: Int = open(file.path, O_RDONLY).also { if (it < 0) throw ParquetFormatException("cannot open ${file.path}: ${strerror(errno)?.toKString()}") }
    private val closed = AtomicInt(0)
    private val size: Long
    private val id: SourceId

    init {
        val (length, mtime) = memScoped {
            val st = alloc<stat>()
            if (fstat(fd, st.ptr) != 0) {
                platform.posix.close(fd)
                throw ParquetFormatException("cannot stat ${file.path}: ${strerror(errno)?.toKString()}")
            }
            st.st_size to (st.st_mtimespec.tv_sec * 1_000_000_000L + st.st_mtimespec.tv_nsec)
        }
        size = length
        id = SourceId(file.path, size, "$size-$mtime")
    }

    override suspend fun id(): SourceId = id

    override suspend fun readTail(n: Int): ByteView {
        val length = minOf(n.coerceAtLeast(0).toLong(), size).toInt()
        return withContext(Dispatchers.IO) { read(size - length, length) }
    }

    override suspend fun readRanges(ranges: List<LongRange>): List<ByteView> = withContext(Dispatchers.IO) {
        ranges.map { r -> if (r.isEmpty()) ByteView.EMPTY else rangeLength(r).let { len -> checkRange(r.first, len, size); read(r.first, len) } }
    }

    private fun read(offset: Long, length: Int): ByteView {
        if (closed.value != 0) throw IllegalStateException("${file.path} is closed")
        val out = ByteArray(length)
        if (length == 0) return ByteView(out)
        out.usePinned { pin ->
            var done = 0
            while (done < length) {
                val n = pread(fd, pin.addressOf(0) + done, (length - done).convert(), offset + done)
                when {
                    n > 0 -> done += n.toInt()
                    n == 0L -> throw ParquetFormatException("${file.path} ended at ${offset + done} of $size bytes")
                    errno == EINTR -> Unit
                    else -> throw ParquetFormatException("cannot read ${file.path}: ${strerror(errno)?.toKString()}")
                }
            }
        }
        return ByteView(out)
    }

    override fun close() {
        if (closed.compareAndSet(0, 1)) platform.posix.close(fd)
    }
}
