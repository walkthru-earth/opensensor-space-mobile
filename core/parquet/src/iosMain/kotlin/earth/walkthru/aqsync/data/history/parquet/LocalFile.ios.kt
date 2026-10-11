@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package earth.walkthru.aqsync.data.history.parquet

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.Foundation.NSData
import platform.Foundation.NSDataReadingMappedIfSafe
import platform.Foundation.NSError
import platform.Foundation.dataWithContentsOfFile
import platform.posix.memcpy

/** A file on iOS, by absolute [path] (for example inside the app's Application Support archive). */
actual class LocalFile(val path: String)

internal actual fun LocalFile.mapReadOnly(): ByteReader = MappedDataReader(path)

internal actual val LocalFile.displayName: String get() = path.substringAfterLast('/')

/**
 * A [ByteReader] over `NSData` read with `NSDataReadingMappedIfSafe`: Foundation maps the file when it
 * can (local volumes) and reads it otherwise. Reads copy only the requested range. Thread-safe: the
 * data object is immutable. [close] drops the reader's reference, and Foundation unmaps the file when
 * the last reference goes.
 *
 * @throws ParquetFormatException when the file cannot be opened.
 */
class MappedDataReader(path: String) : ByteReader {
    private var data: NSData? = memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        NSData.dataWithContentsOfFile(path, NSDataReadingMappedIfSafe, error.ptr)
            ?: throw ParquetFormatException("cannot open $path: ${error.value?.localizedDescription ?: "unknown error"}")
    }

    override val size: Long = data!!.length.toLong()

    /** Whether [close] released the data. */
    val isClosed: Boolean get() = data == null

    override fun read(offset: Long, length: Int): ByteArray {
        val bytes = checkNotNull(data) { "reader is closed" }
        checkRange(offset, length, size)
        val out = ByteArray(length)
        if (length == 0) return out
        val base = bytes.bytes?.reinterpret<ByteVar>() ?: throw ParquetFormatException("mapped file has no bytes")
        out.usePinned { pinned -> memcpy(pinned.addressOf(0), base + offset, length.convert()) }
        return out
    }

    override fun close() {
        data = null
    }
}
