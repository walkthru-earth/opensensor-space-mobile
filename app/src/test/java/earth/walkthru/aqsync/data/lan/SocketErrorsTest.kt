package earth.walkthru.aqsync.data.lan

import earth.walkthru.aqsync.link.DisconnectCause
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException

class SocketErrorsTest {
    @Test
    fun `Android and host socket texts map to the same causes`() {
        assertEquals(DisconnectCause.PeerClosed(), SocketErrors.cause(EOFException()))
        assertEquals(DisconnectCause.PeerReset, SocketErrors.cause(SocketException("recvfrom failed: ECONNRESET (Connection reset by peer)")))
        assertEquals(DisconnectCause.PeerReset, SocketErrors.cause(SocketException("Connection reset")))
        assertEquals(DisconnectCause.PeerReset, SocketErrors.cause(SocketException("Broken pipe")))
        assertEquals(
            DisconnectCause.LocalAbort(errno = SocketErrors.ECONNABORTED),
            SocketErrors.cause(SocketException("recvfrom failed: ECONNABORTED (Software caused connection abort)")),
        )
        assertEquals(DisconnectCause.LocalAbort(errno = SocketErrors.EPERM), SocketErrors.cause(IOException("x", SocketException("EPERM (Operation not permitted)"))))
        assertEquals(DisconnectCause.LocalAbort(), SocketErrors.cause(IOException("something else")))
    }

    @Test
    fun `connect failures keep the local network permission apart`() {
        assertEquals(DisconnectCause.LocalAbort(errno = SocketErrors.EPERM), SocketErrors.connectCause(SocketException("connect failed: EPERM (Operation not permitted)")))
        assertEquals(DisconnectCause.Unreachable, SocketErrors.connectCause(ConnectException("Connection refused")))
        assertEquals(DisconnectCause.Unreachable, SocketErrors.connectCause(SocketTimeoutException("connect timed out")))
    }
}
