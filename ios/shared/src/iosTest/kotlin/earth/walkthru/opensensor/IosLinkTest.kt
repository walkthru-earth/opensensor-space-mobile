@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package earth.walkthru.opensensor

import earth.walkthru.aqsync.link.AuthKind
import earth.walkthru.aqsync.link.DisconnectCause
import earth.walkthru.aqsync.link.retryable
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Identity is bound once before Swift shares the connection across isolation domains. */
class IosLinkTest {
    private object IdlePort : IosLinkPort {
        override val mtu = 517

        override fun send(frame: NSData, done: (Boolean) -> Unit) = done(false)

        override fun setReadPaused(paused: Boolean) = Unit
    }

    private fun withLink(check: (IosLink) -> Unit) = runTest {
        val link = IosLink(
            IdlePort,
            overWifi = true,
            address = "identity-test-device",
            callbacks = coroutineContext,
            context = backgroundScope.coroutineContext,
        )
        try {
            check(link)
        } finally {
            link.close()
        }
    }

    @Test
    fun identifyBindsStationBootAndReadWindow() = withLink { link ->
        assertNull(link.station)
        assertNull(link.boot)
        assertEquals(0, link.maxRead)

        link.identify("first-station", "full-boot-identity", 4096)

        assertEquals("first-station", link.station)
        assertEquals("full-boot-identity", link.boot)
        assertEquals(4096, link.maxRead)
    }

    @Test
    fun repeatedIdentifyRejectsReplacementAndPreservesOriginalIdentity() = withLink { link ->
        link.identify("original-station", null, 1024)

        assertFailsWith<IllegalStateException> {
            link.identify("replacement-station", "replacement-boot", 8192)
        }

        assertEquals("original-station", link.station)
        assertNull(link.boot)
        assertEquals(1024, link.maxRead)
    }

    @Test
    fun aRemovedPairingIsAnAuthFailureThatNeverRetries() {
        val cause = IosLink.causeOf(IosLink.REASON_BOND_MISSING)
        assertEquals(DisconnectCause.Auth(AuthKind.BOND_MISSING), cause)
        assertFalse(cause.retryable)
        assertTrue(IosLink.causeOf(IosLink.REASON_PEER_CLOSED).retryable)
    }
}
