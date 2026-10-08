@file:OptIn(ExperimentalForeignApi::class)

package earth.walkthru.aqsync.data.history.parquet

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
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
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2_
import platform.zlib.inflateReset
import platform.zlib.z_stream_s
import platform.zlib.zlibVersion

/** gzip window bits for `inflateInit2`: 15-bit window, +16 = expect a gzip header and trailer. */
private const val GZIP_WINDOW_BITS = 15 + 16

/**
 * The system zlib (`libz`, part of iOS): inflates one or more concatenated gzip members into exactly
 * [expectedSize] bytes. One spare output byte detects a page that holds more than its header says.
 */
internal actual fun gunzip(input: ByteArray, expectedSize: Int): ByteArray {
    if (input.isEmpty()) throw ParquetFormatException("gzip: empty input")
    val out = ByteArray(expectedSize + 1)
    var produced = 0
    memScoped {
        val strm = alloc<z_stream_s>()
        memset(strm.ptr, 0, sizeOf<z_stream_s>().convert())
        input.usePinned { inPin ->
            out.usePinned { outPin ->
                strm.next_in = inPin.addressOf(0).reinterpret()
                strm.avail_in = input.size.convert()
                strm.next_out = outPin.addressOf(0).reinterpret()
                strm.avail_out = out.size.convert()
                val version = zlibVersion()?.toKString()
                if (inflateInit2_(strm.ptr, GZIP_WINDOW_BITS, version, sizeOf<z_stream_s>().convert()) != Z_OK) {
                    throw ParquetFormatException("gzip: zlib init failed")
                }
                try {
                    while (true) {
                        val rc = inflate(strm.ptr, Z_NO_FLUSH)
                        when {
                            rc == Z_STREAM_END && strm.avail_in.toInt() == 0 -> break
                            rc == Z_STREAM_END -> if (inflateReset(strm.ptr) != Z_OK) throw ParquetFormatException("gzip: cannot read the next member")
                            rc == Z_OK && strm.avail_out.toInt() == 0 -> throw ParquetFormatException("gzip: page holds more than $expectedSize bytes")
                            rc == Z_OK -> Unit
                            rc == Z_BUF_ERROR && strm.avail_in.toInt() == 0 -> throw ParquetFormatException("gzip: stream truncated")
                            rc == Z_BUF_ERROR -> throw ParquetFormatException("gzip: page holds more than $expectedSize bytes")
                            else -> throw ParquetFormatException("gzip: ${strm.msg?.toKString() ?: "error $rc"}")
                        }
                    }
                    produced = out.size - strm.avail_out.toInt()
                } finally {
                    inflateEnd(strm.ptr)
                }
            }
        }
    }
    if (produced != expectedSize) throw ParquetFormatException("gzip: produced $produced of $expectedSize bytes")
    return out.copyOf(expectedSize)
}
