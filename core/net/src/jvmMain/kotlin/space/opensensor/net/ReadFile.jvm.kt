package space.opensensor.net

import java.io.File
import java.io.FileNotFoundException

internal actual fun readFileOrNull(path: String): ByteArray? {
    val file = File(path)
    if (!file.isFile) return null
    return try {
        file.readBytes()
    } catch (e: FileNotFoundException) {
        // The OS purged the cache between the check and the read.
        null
    }
}
