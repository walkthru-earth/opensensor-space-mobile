package space.opensensor.devices

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DevicesControllerTest {
    private class FakeStore(initial: List<KnownSensor>) : KnownDeviceStore {
        override val devices = MutableStateFlow(initial)
    }

    private class FakeRadio : DeviceRadio {
        override val nearby = MutableStateFlow<List<NearbySensor>>(emptyList())
        override val scanning = MutableStateFlow(false)
        override val scanProblem = MutableStateFlow<ScanProblem?>(null)
        override val wifi = MutableStateFlow<List<WifiSensor>>(emptyList())
        override val active = MutableStateFlow<List<ActiveLink>>(emptyList())
        var starts = 0
        var stops = 0
        override fun startScan() {
            starts++
            scanning.value = true
        }
        override fun stopScan() {
            stops++
            scanning.value = false
        }
    }

    private class FakeCollect : CollectAllPort {
        override val running = MutableStateFlow(false)
        var calls = 0
        override fun collectAll() {
            calls++
            running.value = true
        }
    }

    private val a = KnownSensor("A", "Kitchen", "st-a", 1, false)
    private val b = KnownSensor("B", "Balcony", "st-b", 2, false)

    @Test
    fun stateFollowsThePorts() = runTest(UnconfinedTestDispatcher()) {
        val store = FakeStore(listOf(a))
        val radio = FakeRadio()
        val archive = MutableStateFlow(mapOf("st-a" to ArchiveGlance(3, 10, 20)))
        val controller = DevicesController(backgroundScope, store, radio, archive, FakeCollect())

        assertEquals(listOf("A"), controller.state.value.list.known.map { it.id })
        assertEquals(3, controller.state.value.list.known.single().archive?.files)
        assertFalse(controller.state.value.showCollectAll)

        radio.nearby.value = listOf(NearbySensor("A", null, -40))
        assertEquals(listOf("A"), controller.state.value.list.nearby.map { it.id })
        assertTrue(controller.state.value.list.known.isEmpty())

        radio.scanProblem.value = ScanProblem.BLUETOOTH_OFF
        assertEquals(ScanProblem.BLUETOOTH_OFF, controller.state.value.scanProblem)

        store.devices.value = listOf(a, b)
        assertTrue(controller.state.value.showCollectAll)
    }

    @Test
    fun scanActionsReachTheRadio() = runTest(UnconfinedTestDispatcher()) {
        val radio = FakeRadio()
        val controller = DevicesController(backgroundScope, FakeStore(emptyList()), radio, MutableStateFlow(emptyMap()), FakeCollect())
        controller.onAction(DevicesAction.StartScan)
        assertTrue(controller.state.value.scanning)
        controller.onAction(DevicesAction.StopScan)
        assertFalse(controller.state.value.scanning)
        assertEquals(1, radio.starts)
        assertEquals(1, radio.stops)
    }

    @Test
    fun collectAllRunsOnceWhileRunning() = runTest(UnconfinedTestDispatcher()) {
        val collect = FakeCollect()
        val controller = DevicesController(backgroundScope, FakeStore(listOf(a, b)), FakeRadio(), MutableStateFlow(emptyMap()), collect)
        controller.onAction(DevicesAction.CollectAll)
        assertTrue(controller.state.value.collecting)
        controller.onAction(DevicesAction.CollectAll)
        assertEquals(1, collect.calls)
    }
}
