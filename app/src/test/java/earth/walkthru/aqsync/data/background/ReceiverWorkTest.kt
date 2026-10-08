package earth.walkthru.aqsync.data.background

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ReceiverWorkTest {
    private fun kotlinx.coroutines.test.TestScope.appScope(failures: MutableList<Throwable>) =
        CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, e -> failures += e })

    @Test
    fun `hung work is cut at the budget and the broadcast still finishes`() = runTest {
        val failures = mutableListOf<Throwable>()
        var finished = 0
        launchReceiverWork(appScope(failures), "test", finish = { finished++ }) { awaitCancellation() }
        advanceTimeBy(RECEIVER_BUDGET_MS - 1)
        runCurrent()
        assertEquals(0, finished)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, finished)
        assertTrue(RECEIVER_BUDGET_MS < 10_000)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `failure finishes the broadcast and does not escape to the scope`() = runTest {
        val failures = mutableListOf<Throwable>()
        val scope = appScope(failures)
        var finished = 0
        launchReceiverWork(scope, "test", finish = { finished++ }) { throw IOException("boom") }
        val sibling = CompletableDeferred<Unit>()
        launchReceiverWork(scope, "test", finish = { sibling.complete(Unit) }) { }
        advanceUntilIdle()
        assertEquals(1, finished)
        assertTrue("a sibling is not cancelled by the failure", sibling.isCompleted)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `cancelled scope still finishes the broadcast`() = runTest {
        val failures = mutableListOf<Throwable>()
        val scope = appScope(failures)
        var finished = 0
        val job = launchReceiverWork(scope, "test", finish = { finished++ }) { awaitCancellation() }
        runCurrent()
        job.cancel()
        advanceUntilIdle()
        assertEquals(1, finished)
    }
}
