@file:OptIn(ExperimentalForeignApi::class)

package space.opensensor.net

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile
import platform.posix.memcpy

internal actual fun readFileOrNull(path: String): ByteArray? {
    val data = NSData.dataWithContentsOfFile(path) ?: return null
    val count = data.length.toInt()
    val out = ByteArray(count)
    if (count > 0) out.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
    return out
}
