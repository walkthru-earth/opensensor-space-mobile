package space.opensensor.data.lan

import space.opensensor.data.ble.TransportException
import space.opensensor.data.ble.TransportUnavailableException
import space.opensensor.link.DisconnectCause
import space.opensensor.link.UnavailableKind
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

    /**
     * A connect failure. `EPERM` is the Android local network permission, so it is
     * [DisconnectCause.Unavailable] with [UnavailableKind.PERMISSION_MISSING]. Retrying cannot help
     * until the user grants it. Anything else is [DisconnectCause.Unreachable].
     */
    fun connectCause(e: IOException): DisconnectCause =
        if (isPermissionDenied(text(e))) DisconnectCause.Unavailable(UnavailableKind.PERMISSION_MISSING) else DisconnectCause.Unreachable

    /** The typed exception for a refused connect, [TransportUnavailableException] for the permission. */
    fun connectException(e: IOException): TransportException =
        if (isPermissionDenied(text(e))) {
            TransportUnavailableException(UnavailableKind.PERMISSION_MISSING, "local network permission refused")
        } else {
            LanConnectException(LanFailure.UNREACHABLE, null, e)
        }

    private fun text(e: Throwable) = generateSequence(e) { it.cause }.mapNotNull { it.message }.joinToString(" ")

    private fun isPermissionDenied(text: String) = "EPERM" in text || "Operation not permitted" in text
}
