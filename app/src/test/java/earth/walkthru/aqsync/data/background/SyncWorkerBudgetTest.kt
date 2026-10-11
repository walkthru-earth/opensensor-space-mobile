package earth.walkthru.aqsync.data.background

import androidx.work.WorkInfo
import earth.walkthru.aqsync.collection.CollectionIntent
import earth.walkthru.aqsync.collection.CollectionOrigin
import earth.walkthru.aqsync.collection.CollectionScope
import earth.walkthru.aqsync.collection.KeepAlive
import earth.walkthru.aqsync.collection.KeepAliveKind
import earth.walkthru.aqsync.collection.StopSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SyncWorkerBudgetTest {
    private val intent = CollectionIntent(1, "synthetic-device", CollectionScope.All, CollectionOrigin.WORKER)

    @Test
    fun `deadline cancels collection and waits for cleanup before returning`() = runTest {
        val released = CompletableDeferred<Unit>()
        lateinit var protection: KeepAlive

        val result = boundedWorkerRun(100) { alive ->
            protection = alive
            val process = assertNotNullLease(alive)
            try {
                awaitCancellation()
            } finally {
                process.close()
                released.complete(Unit)
            }
        }

        assertNull(result)
        assertTrue(released.isCompleted)
        assertEquals(100L, testScheduler.currentTime)
        assertNull("no collection can start after its job expired", protection.acquire(KeepAliveKind.PROCESS, intent))
    }

    @Test
    fun `finished worker refuses later PROCESS and never grants a WIFI lease`() = runTest {
        lateinit var protection: KeepAlive
        val result = boundedWorkerRun(100) { alive ->
            protection = alive
            assertNotNullLease(alive).close()
            assertNull(alive.acquire(KeepAliveKind.WIFI, intent))
            "finished"
        }
        assertEquals("finished", result)
        assertNull(protection.acquire(KeepAliveKind.PROCESS, intent))
    }

    @Test
    fun `system quota cancellation propagates instead of returning a retry result`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val cleaned = CompletableDeferred<Unit>()
        var returned = false
        val worker = launch {
            boundedWorkerRun(100) {
                entered.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cleaned.complete(Unit)
                }
            }
            returned = true
        }
        entered.await()
        worker.cancel(CancellationException("synthetic quota stop"))
        worker.join()
        assertTrue(cleaned.isCompleted)
        assertTrue(worker.isCancelled)
        assertTrue(!returned)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun `nested connect timeout stays distinct from the worker deadline`() = runTest {
        var timedOut = false
        try {
            boundedWorkerRun(100) { withTimeout(10) { delay(20); "unreachable" } }
        } catch (_: CancellationException) {
            timedOut = true
        }
        assertTrue(timedOut)
        assertEquals(10L, testScheduler.currentTime)
    }

    @Test
    fun `budget leaves headroom below ordinary WorkManager limit`() {
        assertTrue(SyncWorker.MAX_RUN_MS in 1 until 10 * 60_000L)
    }

    @Test
    fun `notification cancellation is USER while quota and timeout are SYSTEM`() {
        assertEquals(StopSource.USER, workerStopSource(WorkInfo.STOP_REASON_CANCELLED_BY_APP))
        assertEquals(StopSource.USER, workerStopSource(WorkInfo.STOP_REASON_USER))
        assertEquals(StopSource.FGS_TIMEOUT, workerStopSource(WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT))
        assertEquals(StopSource.SYSTEM, workerStopSource(WorkInfo.STOP_REASON_QUOTA))
        assertEquals(StopSource.SYSTEM, workerStopSource(WorkInfo.STOP_REASON_TIMEOUT))
        assertEquals(StopSource.SYSTEM, workerStopSource(WorkInfo.STOP_REASON_NOT_STOPPED))
    }

    private fun assertNotNullLease(protection: KeepAlive) = protection.acquire(KeepAliveKind.PROCESS, intent).also {
        assertNotNull("the executing WorkManager job provides PROCESS protection", it)
    }!!
}
