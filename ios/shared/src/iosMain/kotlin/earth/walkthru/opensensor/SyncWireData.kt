@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package earth.walkthru.opensensor

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.memcpy

// Swift sees these as SyncWire and SyncReply members that take and return Data. Each call
// copies the frame once with memcpy. The previous hex bridge built one String per byte.

internal fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

internal fun NSData.toByteArray(): ByteArray {
    val count = length.toInt()
    val out = ByteArray(count)
    if (count > 0) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}

fun SyncWire.tokenRequest(): NSData = tokenFrame().toNSData()
fun SyncWire.statusRequest(): NSData = statusFrame().toNSData()

/** LAN length envelope around one request body. */
fun SyncWire.envelope(body: NSData): NSData = envelopeFrame(body.toByteArray()).toNSData()

/** Builds the OSS1 handshake. Kotlin copies of the token are zeroed before returning, and no String copy exists. */
fun SyncWire.handshake(token: NSData): NSData {
    val secret = token.toByteArray()
    try {
        val frame = handshakeFrame(secret)
        try {
            return frame.toNSData()
        } finally {
            frame.fill(0)
        }
    } finally {
        secret.fill(0)
    }
}

/** Decodes one frame body (without the LAN length prefix). Malformed frames return null. */
fun SyncWire.decode(data: NSData): SyncReply? = decodeFrame(data.toByteArray())

/** CHUNK payload or TOKEN bytes, empty for other kinds. */
val SyncReply.payload: NSData get() = bytes.toNSData()
