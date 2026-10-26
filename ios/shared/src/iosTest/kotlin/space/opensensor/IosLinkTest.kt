@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package space.opensensor

import kotlinx.coroutines.test.runTest
import platform.Foundation.NSData
import space.opensensor.link.AuthKind
import space.opensensor.link.DisconnectCause
import space.opensensor.link.retryable
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
    fun identifyBindsThingBootAndReadWindow() = withLink { link ->
        assertNull(link.thing)
        assertNull(link.boot)
        assertEquals(0, link.maxRead)

        link.identify("first-thing", "full-boot-identity", 4096)

        assertEquals("first-thing", link.thing)
        assertEquals("full-boot-identity", link.boot)
        assertEquals(4096, link.maxRead)
    }

    @Test
    fun repeatedIdentifyRejectsReplacementAndPreservesOriginalIdentity() = withLink { link ->
        link.identify("original-thing", null, 1024)

        assertFailsWith<IllegalStateException> {
            link.identify("replacement-thing", "replacement-boot", 8192)
        }

        assertEquals("original-thing", link.thing)
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
