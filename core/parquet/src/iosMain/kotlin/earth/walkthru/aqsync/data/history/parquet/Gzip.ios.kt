@file:OptIn(ExperimentalForeignApi::class)

package earth.walkthru.aqsync.data.history.parquet

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.memset
import platform.zlib.Z_BUF_ERROR
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.inflate
import platform.zlib.inflateInit2_
import platform.zlib.inflateReset
import platform.zlib.z_stream_s
import platform.zlib.zlibVersion

/** gzip window bits for `inflateInit2`: 15-bit window, +16 = expect a gzip header and trailer. */
private const val GZIP_WINDOW_BITS = 15 + 16

/**
 * One zlib stream and probe byte per thread, initialised once and reset per page instead of
 * `inflateInit2`/`inflateEnd` per call. They live as long as the thread (about 40 KiB each).
 */
@kotlin.native.concurrent.ThreadLocal
private object ZState {
    val strm: z_stream_s = nativeHeap.alloc<z_stream_s>().also { s ->
        memset(s.ptr, 0, sizeOf<z_stream_s>().convert())
        if (inflateInit2_(s.ptr, GZIP_WINDOW_BITS, zlibVersion()?.toKString(), sizeOf<z_stream_s>().convert()) != Z_OK) {
            throw ParquetFormatException("gzip: zlib init failed")
        }
    }
    val probe: ByteVar = nativeHeap.alloc<ByteVar>()
}

/**
 * The system zlib (`libz`, part of iOS): inflates one or more concatenated gzip members into exactly
 * [dstLength] bytes of [dst]. Once the output is full, one probe byte detects a page that holds more
 * than its header says.
 */
internal actual fun gunzipInto(src: ByteArray, srcOffset: Int, srcLength: Int, dst: ByteArray, dstOffset: Int, dstLength: Int): Int {
    if (srcLength <= 0) throw ParquetFormatException("gzip: empty input")
    if (srcOffset < 0 || srcOffset.toLong() + srcLength > src.size) throw ParquetFormatException("gzip: input window outside the buffer")
    if (dstOffset < 0 || dstLength < 0 || dstOffset.toLong() + dstLength > dst.size) throw ParquetFormatException("gzip: output window outside the buffer")
    val strm = ZState.strm
    if (inflateReset(strm.ptr) != Z_OK) throw ParquetFormatException("gzip: zlib reset failed")
    var produced = 0
    src.usePinned { inPin ->
        // A zero-length page still needs a valid pointer for zlib.
        val target = if (dst.isEmpty()) ByteArray(1) else dst
        target.usePinned { outPin ->
            strm.next_in = inPin.addressOf(srcOffset).reinterpret()
            strm.avail_in = srcLength.convert()
            strm.next_out = outPin.addressOf(if (dst.isEmpty()) 0 else dstOffset).reinterpret()
            strm.avail_out = dstLength.convert()
            var probing = false
            try {
                while (true) {
                    val rc = inflate(strm.ptr, Z_NO_FLUSH)
                    if (probing && strm.avail_out.toInt() == 0) throw ParquetFormatException("gzip: page holds more than $dstLength bytes")
                    when {
                        rc == Z_STREAM_END && strm.avail_in.toInt() == 0 -> break
                        rc == Z_STREAM_END -> if (inflateReset(strm.ptr) != Z_OK) throw ParquetFormatException("gzip: cannot read the next member")
                        (rc == Z_OK || rc == Z_BUF_ERROR) && strm.avail_out.toInt() == 0 && !probing -> {
                            probing = true
                            strm.next_out = ZState.probe.ptr.reinterpret()
                            strm.avail_out = 1.convert()
                        }
                        rc == Z_OK -> Unit
                        rc == Z_BUF_ERROR -> throw ParquetFormatException("gzip: stream truncated")
                        else -> throw ParquetFormatException("gzip: ${strm.msg?.toKString() ?: "error $rc"}")
                    }
                }
                produced = if (probing) dstLength else dstLength - strm.avail_out.toInt()
            } finally {
                strm.next_in = null
                strm.next_out = null
            }
        }
    }
    if (produced != dstLength) throw ParquetFormatException("gzip: produced $produced of $dstLength bytes")
    return dstLength
}
