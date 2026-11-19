@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package space.opensensor

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
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

/**
 * The kind of one frame body from its type byte, read in place with no copy. A REPLY then goes to
 * `IosLink.offer` undecoded, so a file frame is copied into Kotlin and decoded once.
 */
fun SyncWire.frameKind(data: NSData): FrameKind {
    val bytes = data.bytes ?: return FrameKind.INVALID
    if (data.length == 0uL) return FrameKind.INVALID
    return frameKindOf(bytes.reinterpret<UByteVar>()[0].toInt())
}

/** The shared HELLO check on one frame body, against the saved thing and hardware id. */
fun SyncWire.helloCheck(data: NSData, thing: String, device: String): HelloCheck = helloCheckFrame(data.toByteArray(), thing, device)

/** CHUNK payload or TOKEN bytes, empty for other kinds. */
val SyncReply.payload: NSData get() = bytes.toNSData()
