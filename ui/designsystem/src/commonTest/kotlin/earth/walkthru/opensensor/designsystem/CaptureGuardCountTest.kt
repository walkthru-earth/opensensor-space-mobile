package earth.walkthru.opensensor.designsystem

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CaptureGuardCountTest {
    @Test
    fun theProtectionStaysOnUntilTheLastCredentialFieldLeaves() {
        val count = CaptureGuardCount()
        assertTrue(count.acquire(), "the first field turns the protection on")
        assertFalse(count.acquire(), "a second field shares it")
        assertFalse(count.release(), "one field left, the protection stays on")
        assertTrue(count.release(), "the last field turns it off")
        assertEquals(0, count.holders)
    }

    @Test
    fun anExtraReleaseNeverGoesBelowZero() {
        val count = CaptureGuardCount()
        assertFalse(count.release())
        assertEquals(0, count.holders)
        assertTrue(count.acquire())
    }
}
