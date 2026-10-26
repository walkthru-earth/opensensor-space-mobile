package space.opensensor.device

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class PendingFixTest {
    @Test
    fun aSecondRequestEndsTheFirstWithNoCell() = runTest {
        var starts = 0
        var stops = 0
        val fix = PendingFix(timeoutMs = 15_000, start = { starts++ }, stop = { stops++ }, post = { it() })
        val first = async { fix.await() }
        testScheduler.runCurrent()
        val second = async { fix.await() }
        testScheduler.runCurrent()
        assertNull(first.await())
        fix.finish("85283473fffffff")
        assertEquals("85283473fffffff", second.await())
        assertEquals(2, starts)
    }

    @Test
    fun noFixEndsWithNoCellAfterTheTimeout() = runTest {
        var stops = 0
        val fix = PendingFix(timeoutMs = 15_000, start = {}, stop = { stops++ }, post = { it() })
        val wait = async { fix.await() }
        testScheduler.advanceTimeBy(15_001)
        assertNull(wait.await())
        // Updates stop when the wait gives up, so no request keeps the radio busy.
        assertEquals(true, stops >= 1)
        // A fix arriving after the timeout answers nobody.
        fix.finish("85283473fffffff")
    }

    @Test
    fun aCancelledWaitDoesNotEndANewerOne() = runTest {
        val fix = PendingFix(timeoutMs = 15_000, start = {}, stop = {}, post = { it() })
        val first = async { fix.await() }
        testScheduler.runCurrent()
        first.cancel()
        testScheduler.runCurrent()
        val second = async { fix.await() }
        testScheduler.runCurrent()
        fix.finish("85283473fffffff")
        assertEquals("85283473fffffff", second.await())
    }
}
