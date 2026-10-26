package space.opensensor.data.ble

import android.bluetooth.le.ScanCallback
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class BleScanPlanTest {
    private class FakeRadio(private val now: () -> Long) : ScanRadio {
        val starts = mutableListOf<Pair<ScanMode, Long>>()
        var stops = 0
        var running = false
        lateinit var send: (BleScanner.Event) -> Unit

        override fun start(mode: ScanMode) {
            starts += mode to now()
            running = true
        }

        override fun stop() {
            stops++
            running = false
        }
    }

    private fun TestScope.plan(budget: ScanStartBudget = ScanStartBudget()): Pair<FakeRadio, kotlinx.coroutines.flow.Flow<BleScanner.Event>> {
        val now = { testScheduler.currentTime }
        val radio = FakeRadio(now)
        val flow = BleScanner.planned(ScanPlan(), budget, now) { send -> radio.also { it.send = send } }
        return radio to flow
    }

    private fun seen(at: Long) = BleScanner.Event.Seen(ScannedDevice("AA:BB", "AQ", -60, false, at))

    @Test
    fun `without results the scan steps down at lowLatencyMs and stops at totalMs`() = runTest {
        val (radio, flow) = plan()
        var completedAt = -1L
        launch {
            flow.toList()
            completedAt = testScheduler.currentTime
        }
        runCurrent()
        assertEquals(listOf(ScanMode.LOW_LATENCY to 0L), radio.starts)
        advanceTimeBy(10_001)
        assertEquals(listOf(ScanMode.LOW_LATENCY to 0L, ScanMode.BALANCED to 10_000L), radio.starts)
        advanceTimeBy(40_000)
        assertEquals(45_000L, completedAt)
        assertEquals(2, radio.starts.size)
        assertTrue(!radio.running)
    }

    @Test
    fun `the first result steps down after the settle time`() = runTest {
        val (radio, flow) = plan()
        val events = mutableListOf<BleScanner.Event>()
        launch { flow.toList(events) }
        advanceTimeBy(1_000)
        radio.send(seen(1_000))
        advanceTimeBy(2_001)
        assertEquals(listOf(ScanMode.LOW_LATENCY to 0L, ScanMode.BALANCED to 3_000L), radio.starts)
        advanceTimeBy(60_000)
        assertEquals(1, events.size)
        assertEquals(2, radio.starts.size)
        assertTrue(!radio.running)
    }

    @Test
    fun `cancelling stops the radio before the timebox`() = runTest {
        val (radio, flow) = plan()
        val job = launch { flow.toList() }
        advanceTimeBy(5_000)
        job.cancel()
        runCurrent()
        assertEquals(1, radio.starts.size)
        assertTrue(!radio.running)
    }

    @Test
    fun `the step down is skipped when it would spend the last start in 30 s`() = runTest {
        val budget = ScanStartBudget()
        repeat(3) { assertTrue(budget.tryAcquire(0)) }
        val (radio, flow) = plan(budget)
        launch { flow.toList() }
        advanceTimeBy(46_000)
        assertEquals(listOf(ScanMode.LOW_LATENCY to 0L), radio.starts)
        assertTrue(!radio.running)
    }

    @Test
    fun `starts older than the window free the budget`() {
        val budget = ScanStartBudget()
        repeat(5) { assertTrue(budget.tryAcquire(0)) }
        assertTrue(!budget.tryAcquire(29_999))
        assertTrue(budget.tryAcquire(30_000))
    }

    @Test
    fun `rapid reopen cannot exceed five starts including step downs`() = runTest {
        val budget = ScanStartBudget()
        val starts = mutableListOf<Long>()
        repeat(4) { session ->
            val (radio, flow) = plan(budget)
            val events = mutableListOf<BleScanner.Event>()
            val job = launch { flow.toList(events) }
            runCurrent()
            if (session == 3) {
                assertTrue(job.isCompleted)
                assertEquals(ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY, (events.single() as BleScanner.Event.Failed).errorCode)
                assertTrue(radio.starts.isEmpty())
            } else {
                radio.send(seen(testScheduler.currentTime))
                advanceTimeBy(3_000)
                job.cancelAndJoin()
                starts += radio.starts.map { it.second }
                assertTrue(!radio.running)
            }
        }
        assertEquals(listOf(0L, 2_000L, 3_000L, 5_000L, 6_000L), starts)
    }

    @Test
    fun `background admissions and foreground reopen share one budget`() = runTest {
        val budget = ScanStartBudget()
        repeat(4) { assertTrue(budget.tryAcquire(testScheduler.currentTime, reserveStarts = 1)) }
        assertTrue(!budget.tryAcquire(testScheduler.currentTime, reserveStarts = 1))
        val (radio, flow) = plan(budget)
        val job = launch { flow.toList() }
        runCurrent()
        job.cancelAndJoin()
        assertEquals(1, radio.starts.size)
        val (deniedRadio, deniedFlow) = plan(budget)
        assertTrue(deniedFlow.toList().single() is BleScanner.Event.Failed)
        assertTrue(deniedRadio.starts.isEmpty())
    }

    @Test
    fun `competing admissions cannot both claim the last slot`() {
        val budget = ScanStartBudget()
        repeat(4) { assertTrue(budget.tryAcquire(0)) }
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val threads = Executors.newFixedThreadPool(2)
        try {
            val attempts = List(2) {
                threads.submit<Boolean> {
                    ready.countDown()
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    budget.tryAcquire(0)
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            assertEquals(1, attempts.count { it.get(5, TimeUnit.SECONDS) })
        } finally {
            start.countDown()
            threads.shutdownNow()
        }
    }

    @Test
    fun `a timebox shorter than step down never restarts after completion`() = runTest {
        val now = { testScheduler.currentTime }
        val radio = FakeRadio(now)
        BleScanner.planned(ScanPlan(totalMs = 1_000), ScanStartBudget(), now) { radio }.toList()
        advanceTimeBy(20_000)
        assertEquals(listOf(ScanMode.LOW_LATENCY to 0L), radio.starts)
        assertTrue(!radio.running)
    }
}
