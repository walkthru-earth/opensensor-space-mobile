package earth.walkthru.aqsync.data.protocol

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256_CTX
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.CC_SHA256_Final
import platform.CoreCrypto.CC_SHA256_Init
import platform.CoreCrypto.CC_SHA256_Update

/**
 * iOS SHA-256 through CommonCrypto. The `CC_SHA256_CTX` state lives in a Kotlin `IntArray` (the
 * struct holds only 32-bit words) and is pinned for each call, so nothing is allocated natively
 * and an abandoned hash cannot leak.
 */
@OptIn(ExperimentalForeignApi::class)
actual class Sha256 actual constructor() {
    private val state = IntArray((sizeOf<CC_SHA256_CTX>().toInt() + 3) / 4)
    private var finished = false

    init {
        state.usePinned { CC_SHA256_Init(it.addressOf(0).reinterpret()) }
    }

    actual fun update(bytes: ByteArray, offset: Int, length: Int) {
        check(!finished) { "SHA-256 already finished" }
        checkRange(bytes.size, offset, length)
        if (length == 0) return
        state.usePinned { ctx ->
            bytes.usePinned { data ->
                CC_SHA256_Update(ctx.addressOf(0).reinterpret(), data.addressOf(offset), length.convert())
            }
        }
    }

    actual fun digest(): ByteArray {
        check(!finished) { "SHA-256 already finished" }
        finished = true
        val out = ByteArray(CC_SHA256_DIGEST_LENGTH)
        state.usePinned { ctx ->
            out.usePinned { md -> CC_SHA256_Final(md.addressOf(0).reinterpret(), ctx.addressOf(0).reinterpret()) }
        }
        return out
    }
}
