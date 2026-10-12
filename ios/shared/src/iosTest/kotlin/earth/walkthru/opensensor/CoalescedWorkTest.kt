package earth.walkthru.opensensor

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class CoalescedWorkTest {
    @Test
    fun aRequestReturnsBeforeTheWorkRuns() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + StandardTestDispatcher(testScheduler))
        var runs = 0
        val work = CoalescedWork(scope, "test") { runs++ }
        // The sync lease holder asks and goes on at once. Nothing runs inside its call.
        work.request()
        assertEquals(0, runs)
        testScheduler.runCurrent()
        assertEquals(1, runs)
    }

    @Test
    fun requestsDuringARunCoalesceIntoOneMore() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + StandardTestDispatcher(testScheduler))
        val gate = CompletableDeferred<Unit>()
        var runs = 0
        val work = CoalescedWork(scope, "test") {
            runs++
            if (runs == 1) gate.await()
        }
        work.request()
        testScheduler.runCurrent()
        repeat(5) { work.request() }
        gate.complete(Unit)
        testScheduler.runCurrent()
        assertEquals(2, runs)
    }

    @Test
    fun aFailedRunKeepsLaterRequestsWorking() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + StandardTestDispatcher(testScheduler))
        var runs = 0
        val work = CoalescedWork(scope, "test") {
            runs++
            if (runs == 1) error("footer unreadable")
        }
        work.request()
        testScheduler.runCurrent()
        work.request()
        testScheduler.runCurrent()
        assertEquals(2, runs)
    }
}
