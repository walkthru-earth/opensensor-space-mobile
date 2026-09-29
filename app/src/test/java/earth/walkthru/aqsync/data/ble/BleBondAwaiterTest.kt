package earth.walkthru.aqsync.data.ble

import earth.walkthru.aqsync.data.ble.BleBondAwaiter.State
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BleBondAwaiterTest {
    @Test
    fun `initial NONE waits for asynchronous bonding and owner PIN entry`() = runTest {
        val bond = BleBondAwaiter(State.NONE)
        val beforeCreateBond = bond.current
        val result = async { bond.awaitResult(beforeCreateBond, 120_000) }
        runCurrent()
        assertFalse("createBond returning true does not mean pairing was cancelled", result.isCompleted)
        advanceTimeBy(1_000)
        bond.update(State.BONDING)
        runCurrent()
        advanceTimeBy(60_000)
        assertFalse("The owner must have time to enter the PIN", result.isCompleted)
        bond.update(State.BONDED)
        runCurrent()
        assertTrue(result.await())
    }

    @Test
    fun `already BONDING waits for a terminal result`() = runTest {
        val bond = BleBondAwaiter(State.BONDING)
        val result = async { bond.awaitResult(bond.current, 120_000) }
        runCurrent()
        assertFalse(result.isCompleted)
        bond.update(State.BONDED)
        assertTrue(result.await())
    }

    @Test
    fun `already BONDED succeeds immediately`() = runTest {
        val bond = BleBondAwaiter(State.BONDED)
        assertTrue(bond.awaitResult(bond.current, 120_000))
    }

    @Test
    fun `BONDING to NONE is terminal cancellation`() = runTest {
        val bond = BleBondAwaiter(State.BONDING)
        val beforeRequest = bond.current
        val result = async { bond.awaitResult(beforeRequest, 120_000) }
        runCurrent()
        bond.update(State.NONE)
        assertFalse(result.await())
    }

    @Test
    fun `fast BONDING then NONE is remembered even before collector starts`() = runTest {
        val bond = BleBondAwaiter(State.NONE)
        val beforeCreateBond = bond.current
        bond.update(State.BONDING)
        bond.update(State.NONE)
        assertFalse(bond.awaitResult(beforeCreateBond, 120_000))
    }

    @Test
    fun `broadcast previous BONDING detects cancellation when the intermediate broadcast was missed`() = runTest {
        val bond = BleBondAwaiter(State.NONE)
        val beforeCreateBond = bond.current
        bond.update(State.NONE, previousState = State.BONDING)
        assertFalse(bond.awaitResult(beforeCreateBond, 120_000))
    }

    @Test
    fun `new attempt ignores an earlier cancellation and owner forgetting an old bond`() = runTest {
        val bond = BleBondAwaiter(State.BONDED)
        bond.update(State.BONDING)
        bond.update(State.NONE)
        val beforeNewRequest = bond.update(State.NONE)
        val result = async { bond.awaitResult(beforeNewRequest, 120_000) }
        runCurrent()
        assertFalse(result.isCompleted)
        bond.update(State.BONDED)
        assertTrue(result.await())
    }

    @Test
    fun `waiting remains cancellable and bounded to the pairing timeout`() = runTest {
        val bond = BleBondAwaiter(State.NONE)
        val cancelled = async { bond.awaitResult(bond.current, 120_000) }
        runCurrent()
        cancelled.cancelAndJoin()
        assertTrue(cancelled.isCancelled)
        val timedOut = async { runCatching { bond.awaitResult(bond.current, 120_000) }.exceptionOrNull() }
        runCurrent()
        advanceTimeBy(119_999)
        assertFalse(timedOut.isCompleted)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(timedOut.await() is TimeoutCancellationException)
    }
}
