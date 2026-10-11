package earth.walkthru.aqsync.data.ble

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
        repeat(3) { budget.record(0) }
        val (radio, flow) = plan(budget)
        launch { flow.toList() }
        advanceTimeBy(46_000)
        assertEquals(listOf(ScanMode.LOW_LATENCY to 0L), radio.starts)
        assertTrue(!radio.running)
    }

    @Test
    fun `starts older than the window free the budget`() {
        val budget = ScanStartBudget()
        repeat(4) { budget.record(0) }
        assertTrue(!budget.allowsRestart(29_999))
        assertTrue(budget.allowsRestart(30_000))
    }
}
