package space.opensensor.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class DeviceOwnershipTest {
    @Test
    fun `hardware identity claim rejects a competing endpoint case insensitively`() {
        val claims = DeviceIdentityClaims()
        val first = requireNotNull(claims.claim("A4CB8FD77500", Any()))
        assertNull(claims.claim("a4cb8fd77500", Any()))
        assertNotNull(claims.claim("441bf6e26b40", Any()))
        first.close()
        assertNotNull(claims.claim("a4cb8fd77500", Any()))
    }

    @Test
    fun `stale lease cannot release a replacement owned by the same controller`() {
        val claims = DeviceIdentityClaims()
        val controller = Any()
        val old = requireNotNull(claims.claim("a4cb8fd77500", controller))
        old.close()
        val replacement = requireNotNull(claims.claim("a4cb8fd77500", controller))
        old.close()
        assertNull("a stale close must not free a newer generation", claims.claim("a4cb8fd77500", Any()))
        replacement.close()
        assertNotNull(claims.claim("a4cb8fd77500", Any()))
    }

    @Test
    fun `shared collection budget limits concurrent transfers to two`() = runBlocking {
        withTimeout(5_000) {
            val budget = CollectionBudget()
            val active = AtomicInteger()
            val maximum = AtomicInteger()
            val twoStarted = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val jobs = List(5) {
                async {
                    budget.acquire()
                    try {
                        val count = active.incrementAndGet()
                        maximum.updateAndGet { before -> maxOf(before, count) }
                        if (count == 2) twoStarted.complete(Unit)
                        release.await()
                    } finally {
                        active.decrementAndGet()
                        budget.release()
                    }
                }
            }
            twoStarted.await()
            assertEquals(2, active.get())
            release.complete(Unit)
            jobs.awaitAll()
            assertEquals(2, maximum.get())
            assertEquals(0, active.get())
        }
    }

    @Test
    fun `cancelled queued transfer does not consume or release another owner's permit`() = runBlocking {
        withTimeout(5_000) {
            val budget = CollectionBudget()
            budget.acquire()
            budget.acquire()
            val waiter = launch(start = CoroutineStart.UNDISPATCHED) { budget.acquire(); error("queued transfer should remain blocked") }
            assertFalse(waiter.isCompleted)
            waiter.cancelAndJoin()
            budget.release()
            budget.acquire()
            budget.release()
            budget.release()
            budget.acquire()
            budget.acquire()
            budget.release()
            budget.release()
        }
    }
}
