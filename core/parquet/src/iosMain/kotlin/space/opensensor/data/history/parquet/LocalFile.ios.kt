@file:OptIn(ExperimentalForeignApi::class)

package space.opensensor.data.history.parquet

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.EINTR
import platform.posix.O_RDONLY
import platform.posix.S_IFMT
import platform.posix.S_IFREG
import platform.posix.errno
import platform.posix.fstat
import platform.posix.open
import platform.posix.pread
import platform.posix.stat
import platform.posix.strerror
import kotlin.concurrent.AtomicInt

/** A file on iOS, by absolute [path] (for example inside the app's Application Support archive). */
actual class LocalFile(val path: String)

internal actual fun LocalFile.openReader(): ByteReader = LocalFileReader(path)

internal actual val LocalFile.displayName: String get() = path.substringAfterLast('/')

/**
 * A [ByteReader] with `pread` on one descriptor, no mapping. Each read copies exactly its range into a
 * new array. Thread-safe. [close] may race reads on other threads, so the descriptor is reference
 * counted: each read holds it, and the last of [close] and the reads in flight closes it. A read can
 * therefore never reach a descriptor number the process has reused for another file, and the
 * descriptor is gone once [close] and those reads return, not when a collector runs (#61).
 * [LocalRangeSource] reads through the same path.
 *
 * @throws ParquetFormatException when the file cannot be opened.
 */
class LocalFileReader(private val path: String) : ByteReader {
    private val fd: Int = open(path, O_RDONLY).also { if (it < 0) throw ParquetFormatException("cannot open $path: ${strerror(errno)?.toKString()}") }

    /** Reads in flight, plus [CLOSED] once [close] ran. */
    private val state = AtomicInt(0)

    override val size: Long

    /** Modification time in nanoseconds, from the same `fstat` as [size]. */
    internal val modifiedNs: Long

    init {
        val (length, mtime) = memScoped {
            val st = alloc<stat>()
            if (fstat(fd, st.ptr) != 0) {
                platform.posix.close(fd)
                throw ParquetFormatException("cannot stat $path: ${strerror(errno)?.toKString()}")
            }
            // `open` accepts a directory read-only, so refuse it here, not at the first read.
            if ((st.st_mode.toInt() and S_IFMT) != S_IFREG) {
                platform.posix.close(fd)
                throw ParquetFormatException("cannot open $path: not a regular file")
            }
            st.st_size to (st.st_mtimespec.tv_sec * 1_000_000_000L + st.st_mtimespec.tv_nsec)
        }
        size = length
        modifiedNs = mtime
    }

    override fun read(offset: Long, length: Int): ByteArray {
        acquire()
        try {
            checkRange(offset, length, size)
            return readOpen(offset, length)
        } finally {
            release()
        }
    }

    private fun readOpen(offset: Long, length: Int): ByteArray {
        val out = ByteArray(length)
        if (length == 0) return out
        out.usePinned { pin ->
            var done = 0
            while (done < length) {
                val n = pread(fd, pin.addressOf(0) + done, (length - done).convert(), offset + done)
                when {
                    n > 0 -> done += n.toInt()
                    n == 0L -> throw ParquetFormatException("$path ended at ${offset + done} of $size bytes")
                    errno == EINTR -> Unit
                    else -> throw ParquetFormatException("cannot read $path: ${strerror(errno)?.toKString()}")
                }
            }
        }
        return out
    }

    private fun acquire() {
        while (true) {
            val s = state.value
            if (s and CLOSED != 0) throw IllegalStateException("$path is closed")
            if (state.compareAndSet(s, s + 1)) return
        }
    }

    /** The read that leaves a closed reader with no other read closes the descriptor. */
    private fun release() {
        if (state.decrementAndGet() == CLOSED) platform.posix.close(fd)
    }

    override fun close() {
        while (true) {
            val s = state.value
            if (s and CLOSED != 0) return
            if (state.compareAndSet(s, s or CLOSED)) {
                if (s == 0) platform.posix.close(fd)
                return
            }
        }
    }

    private companion object {
        const val CLOSED = 1 shl 30
    }
}
