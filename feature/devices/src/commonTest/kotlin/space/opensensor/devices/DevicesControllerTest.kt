package space.opensensor.devices

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import space.opensensor.data.protocol.Advertisement
import space.opensensor.data.protocol.DeviceConfig
import space.opensensor.data.sync.ThingSetupWatch
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

    private val setupFlag = Advertisement.FLAG_SETUP

    private fun TestScope.opens(controller: DevicesController): MutableList<SetupRequest> =
        mutableListOf<SetupRequest>().also { list -> backgroundScope.launch { controller.setupOpens.toList(list) } }

    @Test
    fun oneSensorAskingForSetupOpensTheWizardOnceAsAnOffer() = runTest(UnconfinedTestDispatcher()) {
        val radio = FakeRadio()
        val watch = ThingSetupWatch()
        val controller = DevicesController(backgroundScope, FakeStore(emptyList()), radio, MutableStateFlow(emptyMap()), FakeCollect(), watch)
        val opens = opens(controller)
        assertTrue(opens.isEmpty())

        radio.nearby.value = listOf(NearbySensor("X", "Fresh", -50, advertFlags = setupFlag, seenMs = 5))
        assertEquals(listOf(SetupRequest("X", "Fresh", known = false, auto = true)), opens)
        assertEquals(1, controller.state.value.setupCount)
        assertTrue(controller.state.value.list.nearby.single().needsSetup, "the row keeps its tag after a back out")

        // A fresh advert, or the screen collecting again, does not open it twice.
        radio.nearby.value = listOf(NearbySensor("X", "Fresh", -48, advertFlags = setupFlag, seenMs = 9))
        val again = opens(controller)
        assertEquals(1, opens.size)
        assertTrue(again.isEmpty())
    }

    @Test
    fun aKnownSensorIsOfferedByItselfAndOpensAgainInTheNextSession() = runTest(UnconfinedTestDispatcher()) {
        val radio = FakeRadio()
        val watch = ThingSetupWatch()
        val controller = DevicesController(backgroundScope, FakeStore(listOf(a)), radio, MutableStateFlow(emptyMap()), FakeCollect(), watch)
        val opens = opens(controller)
        radio.nearby.value = listOf(NearbySensor("a", null, -50, advertFlags = setupFlag, seenMs = 5))
        val request = SetupRequest("A", "Kitchen", known = true, auto = true)
        assertEquals(listOf(request), opens)

        watch.endSession(4)
        assertEquals(listOf(request, request), opens, "the next session opens it again")
        watch.endSession(5)
        assertEquals(2, opens.size, "an advert from before the session end does not open it")
        assertEquals(0, controller.state.value.setupCount)
    }

    @Test
    fun aTapOnFinishSetupSpendsThatSensorOnly() = runTest(UnconfinedTestDispatcher()) {
        val radio = FakeRadio()
        val watch = ThingSetupWatch()
        val controller = DevicesController(backgroundScope, FakeStore(listOf(a)), radio, MutableStateFlow(emptyMap()), FakeCollect(), watch)
        controller.onAction(DevicesAction.FinishSetup(SetupRequest("A", "Kitchen", known = true)))
        val opens = opens(controller)
        radio.nearby.value = listOf(NearbySensor("A", null, -50, advertFlags = setupFlag, seenMs = 5))
        assertTrue(opens.isEmpty(), "the wizard already ran for it this session")
        radio.nearby.value = listOf(NearbySensor("X", "Fresh", -50, advertFlags = setupFlag, seenMs = 6))
        assertEquals(listOf(SetupRequest("X", "Fresh", known = false, auto = true)), opens)
    }

    @Test
    fun severalSensorsAskingShowACountAndNeverOpenTheWizard() = runTest(UnconfinedTestDispatcher()) {
        val radio = FakeRadio()
        val watch = ThingSetupWatch()
        val controller = DevicesController(backgroundScope, FakeStore(emptyList()), radio, MutableStateFlow(emptyMap()), FakeCollect(), watch)
        val opens = opens(controller)
        radio.nearby.value = (1..18).map { NearbySensor("S$it", "OS-$it", -50, advertFlags = setupFlag, seenMs = 5) }
        assertEquals(18, controller.state.value.setupCount)
        assertTrue(controller.state.value.list.nearby.all { it.needsSetup })
        assertTrue(opens.isEmpty())
        controller.onAction(DevicesAction.SetupMany)
        assertTrue(opens.isEmpty())
    }

    @Test
    fun theWizardOpensByItselfOncePerSessionAcrossAllSensors() = runTest(UnconfinedTestDispatcher()) {
        val radio = FakeRadio()
        val watch = ThingSetupWatch()
        val controller = DevicesController(backgroundScope, FakeStore(emptyList()), radio, MutableStateFlow(emptyMap()), FakeCollect(), watch)
        val opens = opens(controller)
        radio.nearby.value = listOf(NearbySensor("X", "OS-x", -50, advertFlags = setupFlag, seenMs = 5))
        assertEquals(1, opens.size)

        // X leaves and Y asks alone. The session already spent its one automatic open.
        radio.nearby.value = listOf(NearbySensor("Y", "OS-y", -50, advertFlags = setupFlag, seenMs = 6))
        assertEquals(1, controller.state.value.setupCount)
        assertEquals(1, opens.size)
        assertTrue(controller.state.value.list.nearby.single().needsSetup)
    }

    @Test
    fun pageOneIsTheFallbackWhenTheAdvertHasNoFlag() = runTest(UnconfinedTestDispatcher()) {
        val radio = FakeRadio()
        val watch = ThingSetupWatch()
        val controller = DevicesController(backgroundScope, FakeStore(listOf(a, b)), radio, MutableStateFlow(emptyMap()), FakeCollect(), watch)
        val opens = opens(controller)
        radio.nearby.value = listOf(NearbySensor("A", null, -50, advertFlags = 0, seenMs = 5))
        assertTrue(opens.isEmpty())

        // Device settings read page 1 of B, out of range now, and it has no cell.
        watch.pageRead("B", DeviceConfig.parse("""{"location":{"cell":"","resolution":8},"thing":{"mobility":"fixed"}}"""), 6)
        assertEquals(listOf(SetupRequest("B", "Balcony", known = true, auto = true)), opens)
        assertTrue(controller.state.value.list.known.single { it.id == "B" }.needsSetup)
        assertFalse(controller.state.value.list.nearby.single().needsSetup)
    }

    @Test
    fun withoutAWatchNothingAsksForSetup() = runTest(UnconfinedTestDispatcher()) {
        val radio = FakeRadio()
        val controller = DevicesController(backgroundScope, FakeStore(emptyList()), radio, MutableStateFlow(emptyMap()), FakeCollect())
        val opens = opens(controller)
        radio.nearby.value = listOf(NearbySensor("X", null, -50, advertFlags = setupFlag, seenMs = 5))
        assertTrue(opens.isEmpty())
        assertEquals(0, controller.state.value.setupCount)
        assertFalse(controller.state.value.list.nearby.single().needsSetup)
    }
}
