package space.opensensor.devices

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import space.opensensor.data.protocol.Advertisement
import space.opensensor.data.protocol.DeviceConfig
import space.opensensor.data.sync.ThingSetupWatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

    private val setupFlag = Advertisement.FLAG_SETUP

    @Test
    fun aNewBoardAskingForSetupPromptsOnceAsAnOffer() = runTest(UnconfinedTestDispatcher()) {
        val radio = FakeRadio()
        val watch = ThingSetupWatch()
        val controller = DevicesController(backgroundScope, FakeStore(emptyList()), radio, MutableStateFlow(emptyMap()), FakeCollect(), watch)
        assertNull(controller.state.value.prompt)

        radio.nearby.value = listOf(NearbySensor("X", "Fresh", -50, advertFlags = setupFlag, seenMs = 5))
        val prompt = controller.state.value.prompt
        assertEquals(SetupRequest("X", "Fresh", known = false), prompt)
        assertTrue(controller.state.value.list.nearby.single().needsSetup)

        // The screen sends the prompt back, which spends the open. The row still says it needs setup.
        controller.onAction(DevicesAction.FinishSetup(prompt!!))
        assertNull(controller.state.value.prompt)
        assertTrue(controller.state.value.list.nearby.single().needsSetup)

        // A fresh advert in the same session does not open it again.
        radio.nearby.value = listOf(NearbySensor("X", "Fresh", -48, advertFlags = setupFlag, seenMs = 9))
        assertNull(controller.state.value.prompt)
    }

    @Test
    fun aKnownSensorResumesAndPromptsAgainInTheNextSession() = runTest(UnconfinedTestDispatcher()) {
        val radio = FakeRadio()
        val watch = ThingSetupWatch()
        val controller = DevicesController(backgroundScope, FakeStore(listOf(a)), radio, MutableStateFlow(emptyMap()), FakeCollect(), watch)
        radio.nearby.value = listOf(NearbySensor("a", null, -50, advertFlags = setupFlag, seenMs = 5))
        assertEquals(SetupRequest("A", "Kitchen", known = true), controller.state.value.prompt)

        // The wizard opened from elsewhere, here its own init, also spends the automatic open.
        watch.opened("A")
        assertNull(controller.state.value.prompt)
        watch.endSession()
        assertEquals(SetupRequest("A", "Kitchen", known = true), controller.state.value.prompt)
    }

    @Test
    fun pageOneIsTheFallbackWhenTheAdvertHasNoFlag() = runTest(UnconfinedTestDispatcher()) {
        val radio = FakeRadio()
        val watch = ThingSetupWatch()
        val controller = DevicesController(backgroundScope, FakeStore(listOf(a, b)), radio, MutableStateFlow(emptyMap()), FakeCollect(), watch)
        radio.nearby.value = listOf(NearbySensor("A", null, -50, advertFlags = 0, seenMs = 5))
        assertNull(controller.state.value.prompt)

        // Device settings read page 1 of B, out of range now, and it has no cell.
        watch.pageRead("B", DeviceConfig.parse("""{"location":{"cell":"","resolution":8},"thing":{"mobility":"fixed"}}"""), 6)
        assertEquals(SetupRequest("B", "Balcony", known = true), controller.state.value.prompt)
        assertTrue(controller.state.value.list.known.single { it.id == "B" }.needsSetup)
        assertFalse(controller.state.value.list.nearby.single().needsSetup)
    }

    @Test
    fun withoutAWatchNothingAsksForSetup() = runTest(UnconfinedTestDispatcher()) {
        val radio = FakeRadio()
        val controller = DevicesController(backgroundScope, FakeStore(emptyList()), radio, MutableStateFlow(emptyMap()), FakeCollect())
        radio.nearby.value = listOf(NearbySensor("X", null, -50, advertFlags = setupFlag, seenMs = 5))
        assertNull(controller.state.value.prompt)
        assertFalse(controller.state.value.list.nearby.single().needsSetup)
    }
}
