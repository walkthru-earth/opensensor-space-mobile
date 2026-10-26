package space.opensensor.data.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowRetryPolicyTest {
    @Test
    fun fiveReReadsAreAllowedAndTheSixthLossGivesTheFileUp() {
        for (attempt in 1..5) assertFalse(WindowRetryPolicy.givesUp(attempt))
        assertTrue(WindowRetryPolicy.givesUp(6))
    }

    @Test
    fun backoffGrowsLinearlyFrom300Ms() {
        assertEquals(listOf(300L, 600L, 900L, 1_200L, 1_500L), (1..5).map { WindowRetryPolicy.backoffMs(it) })
    }

    @Test
    fun testsCanShortenTheBase() {
        assertEquals(3L, WindowRetryPolicy.backoffMs(3, baseMs = 1))
    }
}
