package earth.walkthru.aqsync.data.protocol

import java.security.MessageDigest

/** JVM SHA-256 through `MessageDigest`. */
actual class Sha256 actual constructor() {
    private val md = MessageDigest.getInstance("SHA-256")
    private var finished = false

    actual fun update(bytes: ByteArray, offset: Int, length: Int) {
        check(!finished) { "SHA-256 already finished" }
        checkRange(bytes.size, offset, length)
        md.update(bytes, offset, length)
    }

    actual fun digest(): ByteArray {
        check(!finished) { "SHA-256 already finished" }
        finished = true
        return md.digest()
    }
}
