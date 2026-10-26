package space.opensensor.devices

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CollectAllTest {
    private fun known(id: String, station: String?, wifi: Boolean = false) = KnownSensor(id, id, station, null, wifi)

    @Test
    fun wifiWhenTheTokenIsHeldAndTheStationIsOnTheWifiFirst() {
        val plan = CollectAllPlan.plan(
            known = listOf(known("A", "st-a"), known("B", "st-b", wifi = true), known("C", "st-c", wifi = true)),
            wifi = listOf(WifiSensor("aq-b", "st-b")),
            activeIds = emptySet(),
        )
        assertEquals(listOf("B", "A", "C"), plan.map { it.id })
        assertEquals(CollectRoute.WIFI, plan[0].route)
        assertEquals("aq-b", plan[0].wifiName)
        assertEquals(CollectRoute.BLUETOOTH, plan[1].route)
        assertEquals(CollectRoute.BLUETOOTH, plan[2].route, "a token without the sensor on the Wi-Fi uses Bluetooth")
    }

    @Test
    fun skipsOpenLinksAndKeepsOneStepPerStation() {
        val plan = CollectAllPlan.plan(
            known = listOf(known("A", "st-1"), known("A2", "st-1"), known("B", "st-2"), known("N1", null), known("N2", null)),
            wifi = emptyList(),
            activeIds = setOf("B"),
        )
        assertEquals(listOf("A", "N1", "N2"), plan.map { it.id })
    }

    @Test
    fun queueRunsOneAtATimeAndCounts() {
        val steps = listOf("A", "B", "C").map { CollectStep(it, it, "st-$it", CollectRoute.BLUETOOTH) }
        val queue = CollectAllQueue(steps)
        assertEquals(listOf("A"), queue.start().map { it.id })
        assertEquals(setOf("A"), queue.runningIds)
        assertEquals(emptyList(), queue.finished("Z", ok = true), "an unknown id changes nothing")
        assertEquals(listOf("B"), queue.finished("A", ok = true).map { it.id })
        assertEquals(listOf("C"), queue.finished("B", ok = false).map { it.id })
        assertTrue(queue.active)
        assertEquals(emptyList(), queue.finished("C", ok = true))
        assertFalse(queue.active)
        assertEquals(2, queue.succeeded)
        assertEquals(1, queue.failed)
        assertEquals(3, queue.total)
    }

    @Test
    fun queueInParallelNeverRunsOneStationTwice() {
        val steps = listOf(
            CollectStep("A", null, "st-1", CollectRoute.WIFI, "aq-1"),
            CollectStep("A2", null, "st-1", CollectRoute.BLUETOOTH),
            CollectStep("B", null, "st-2", CollectRoute.BLUETOOTH),
        )
        val queue = CollectAllQueue(steps, parallel = 2)
        assertEquals(listOf("A", "B"), queue.start().map { it.id }, "the second step of st-1 is dropped while st-1 runs")
    }

    @Test
    fun cancelDropsPendingButWaitsForRunning() {
        val steps = listOf("A", "B").map { CollectStep(it, it, null, CollectRoute.BLUETOOTH) }
        val queue = CollectAllQueue(steps)
        queue.start()
        queue.cancel()
        assertTrue(queue.active, "A is still running")
        assertEquals(emptyList(), queue.finished("A", ok = false))
        assertFalse(queue.active)
    }

    @Test
    fun parallelMustBePositive() {
        assertFailsWith<IllegalArgumentException> { CollectAllQueue(emptyList(), parallel = 0) }
    }
}
