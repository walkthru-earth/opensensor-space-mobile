package space.opensensor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import space.opensensor.demo.DemoSensor
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The debug demo sensor on iOS (#310) runs the shared pages over the shared `:core:demo` board. */
class IosDemoTest {
    private class Opened {
        val calls = mutableListOf<List<String?>>()
        var accept = true

        fun open(peripheral: String, name: String?, thing: String, device: String): SensorOpenResult {
            calls += listOf(peripheral, name, thing, device)
            return SensorOpenResult(accept, null, null)
        }
    }

    private fun CoroutineScope.demo(state: CompanionState, opened: Opened, sensor: DemoSensor) =
        IosDemo(state, opened::open, main = coroutineContext[ContinuationInterceptor]!!, work = Dispatchers.Default, sensor = sensor)

    private suspend fun until(check: () -> Boolean) = withTimeout(10_000) {
        while (!check()) delay(10)
    }

    @Test
    fun aDebugTestBinaryOffersTheDemo() {
        assertTrue(IosDemo.enabled)
    }

    @Test
    fun openReadsTheBoardIntoTodayAndLendsAnIdentifiedLink() = runBlocking<Unit> {
        val state = CompanionState()
        val opened = Opened()
        val sensor = DemoSensor()
        val demo = demo(state, opened, sensor)

        demo.open()
        until { state.connected }

        assertEquals(IosDemo.PERIPHERAL, state.selectedId)
        assertEquals(sensor.deviceId, state.info?.dev)
        assertEquals(listOf<List<String?>>(listOf(IosDemo.PERIPHERAL, sensor.displayName, sensor.thing, sensor.deviceId)), opened.calls)
        until { state.live != null && state.status != null }

        val link = assertNotNull(demo.linkFor(sensor.deviceId))
        assertEquals(sensor.thing, link.thing)
        assertEquals(sensor.bootId, link.boot)
        assertNull(demo.linkFor("000000000000"))
        val lease = link.acquire(LeaseKind.FILES)
        try {
            val (files, _) = link.session.list()
            assertEquals(sensor.files.map { it.name }.sorted(), files.map { it.name }.sorted())
        } finally {
            lease.end()
        }

        demo.close()
        assertNull(state.selectedId)
        assertFalse(state.connected)
        assertNull(demo.linkFor(sensor.deviceId))
        assertFalse(link.isOpen)
    }

    @Test
    fun aRefusedSensorClosesWithoutALink() = runBlocking<Unit> {
        val state = CompanionState()
        val opened = Opened().apply { accept = false }
        val sensor = DemoSensor()
        val demo = demo(state, opened, sensor)

        demo.open()
        until { opened.calls.isNotEmpty() && state.selectedId == null }

        assertFalse(state.connected)
        assertNull(demo.linkFor(sensor.deviceId))
    }

    @Test
    fun everyOtherIdGoesToSwiftUnchanged() = runBlocking<Unit> {
        val state = CompanionState()
        val demo = demo(state, Opened(), DemoSensor())
        val real = mutableListOf<String>()

        demo.closing { real += "close $it" }("swift-id")
        demo.closingIdle { real += "idle $it" }("swift-id")
        demo.syncing { real += "sync $it" }("swift-id")
        demo.syncingOne { id, name -> real += "one $id $name" }("swift-id", "a.parquet")
        demo.commanding { id, _ -> real += "command $id" }("swift-id", SensorCommand.FLUSH)
        assertNull(demo.linking { real += "link $it"; null }("aabbccddeeff"))
        assertEquals(
            listOf("close swift-id", "idle swift-id", "sync swift-id", "one swift-id a.parquet", "command swift-id", "link aabbccddeeff"),
            real,
        )

        // The demo's own id never reaches Swift.
        real.clear()
        demo.closing { real += it }(IosDemo.PERIPHERAL)
        demo.syncing { real += it }(IosDemo.PERIPHERAL)
        assertTrue(real.isEmpty())
    }

    @Test
    fun openingTheDemoClosesTheRealLinkAndOpeningARealOneClosesTheDemo() = runBlocking<Unit> {
        val state = CompanionState()
        val sensor = DemoSensor()
        val demo = demo(state, Opened(), sensor)
        val calls = mutableListOf<String>()
        val open = demo.opening({ calls += "open $it" }, { calls += "close real" })

        open(IosDemo.PERIPHERAL)
        until { state.connected }
        assertEquals(listOf("close real"), calls)

        open("swift-id")
        assertEquals(listOf("close real", "open swift-id"), calls)
        assertNull(demo.linkFor(sensor.deviceId))
    }
}
