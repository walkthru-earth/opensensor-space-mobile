package earth.walkthru.aqsync.data.lan

import earth.walkthru.aqsync.link.DisconnectCause
import java.io.EOFException
import java.io.IOException

/**
 * Maps a socket failure to a [DisconnectCause] without keeping its text. Android puts the errno
 * name in the message (`recvfrom failed: ECONNABORTED (Software caused connection abort)`), the
 * host JVM puts the C library text (`Connection reset`). Both are matched, nothing else is read.
 */
object SocketErrors {
    const val EPERM = 1
    const val EPIPE = 32
    const val ECONNABORTED = 103
    const val ECONNRESET = 104

    fun cause(e: IOException): DisconnectCause {
        if (e is EOFException) return DisconnectCause.PeerClosed()
        val text = generateSequence<Throwable>(e) { it.cause }.mapNotNull { it.message }.joinToString(" ")
        return when {
            "ECONNRESET" in text || "Connection reset" in text -> DisconnectCause.PeerReset
            "EPIPE" in text || "Broken pipe" in text -> DisconnectCause.PeerReset
            "ECONNABORTED" in text || "connection abort" in text -> DisconnectCause.LocalAbort(errno = ECONNABORTED)
            isPermissionDenied(text) -> DisconnectCause.LocalAbort(errno = EPERM)
            else -> DisconnectCause.LocalAbort()
        }
    }

    /** A connect failure. `EPERM` stays distinct, it is the Android local network permission. */
    fun connectCause(e: IOException): DisconnectCause {
        val text = generateSequence<Throwable>(e) { it.cause }.mapNotNull { it.message }.joinToString(" ")
        return when {
            isPermissionDenied(text) -> DisconnectCause.LocalAbort(errno = EPERM)
            else -> DisconnectCause.Unreachable
        }
    }

    private fun isPermissionDenied(text: String) = "EPERM" in text || "Operation not permitted" in text
}
