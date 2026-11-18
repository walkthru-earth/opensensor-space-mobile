package space.opensensor.setup

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import space.opensensor.data.ble.ConfigSnapshot
import space.opensensor.data.ble.ConnectionState
import space.opensensor.data.protocol.ConfigPatch
import space.opensensor.data.protocol.DeviceConfig
import space.opensensor.data.protocol.DeviceInfo
import space.opensensor.data.protocol.LocationConfig
import space.opensensor.data.protocol.ThingConfig
import space.opensensor.device.DeviceConfigChange
import space.opensensor.device.DeviceConfigController
import space.opensensor.device.DeviceConfigNotice
import space.opensensor.device.DeviceConfigSnapshot
import space.opensensor.device.DeviceConfigSource
import space.opensensor.model.SetupManifest
import space.opensensor.model.StepId
import space.opensensor.settings.DetailLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A ready proto 3 board whose page 1 answers with [location] and [mobility]. Nothing else is called. */
private class LocationSource(var location: LocationConfig, var mobility: String? = null) : DeviceConfigSource {
    override val snapshot = MutableStateFlow(
        DeviceConfigSnapshot(
            address = "AA",
            connection = ConnectionState.Ready(247),
            info = DeviceInfo(
                proto = 3,
                schema = "waveshare-sim7670g-telemetry-v5",
                dict = "50a53393e940be182ded0b1119de6ac6d9521e7d28dfd244a24666ccf2a616c7",
            ),
        ),
    )
    override val notices = MutableSharedFlow<DeviceConfigNotice>()
    override suspend fun readLocation(): DeviceConfig = DeviceConfig(location = location, thing = mobility?.let { ThingConfig(mobility = it) })
    override fun refreshConfig() = Unit
    override fun applyConfig(patch: ConfigPatch, change: DeviceConfigChange) = Unit
    override fun reboot() = Unit
    override fun scanWifi() = Unit
    override fun clearWifiScan() = Unit
    override fun fetchToken() = Unit
    override fun rotateToken() = Unit
    override fun clearBonds() = Unit
    override suspend fun applySensorConfig(patch: ConfigPatch): ConfigSnapshot = error("not used")
    override suspend fun recordSensor(serial: String, vendor: String, model: String, batch: String, source: String) = Unit
    override fun reconnect() = Unit
    override fun reconnectOverBluetooth() = Unit
}

/** System back and the plan from page 1, through the real screen and its Navigation Event handler. */
@OptIn(ExperimentalTestApi::class)
class SetupScreenTest {
    private val dispatcher = NavigationEventDispatcher()
    private val back = DirectNavigationEventInput().also { dispatcher.addInput(it) }
    private val owner = object : NavigationEventDispatcherOwner {
        override val navigationEventDispatcher = dispatcher
    }

    private class Run {
        var controller: SetupController? = null
        var config: DeviceConfigController? = null
        var closes = 0
    }

    private fun ComposeUiTest.show(port: FakePort, source: LocationSource? = null, pick: SetupCandidate? = null): Run {
        val run = Run()
        setContent {
            CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides owner) {
                MaterialTheme { Screen(run, port, source, pick) }
            }
        }
        waitForIdle()
        return run
    }

    @Composable
    private fun Screen(run: Run, port: FakePort, source: LocationSource?, pick: SetupCandidate?) {
        val scope = rememberCoroutineScope()
        val c = run.controller ?: SetupController(scope, port, pick = pick).also { run.controller = it }
        val config = source?.let { s ->
            run.config ?: DeviceConfigController(
                scope,
                s,
                MutableStateFlow(DetailLevel.SIMPLE),
                templates = MutableStateFlow(emptyList()),
                manifestOf = { _, _ -> SetupManifest.NONE },
            ).also { run.config = it }
        }
        SetupScreen(
            controller = c,
            config = config,
            bluetooth = null,
            onConfigAction = {},
            onClose = { run.closes++ },
            onOpenSensor = {},
        )
    }

    private fun ComposeUiTest.pair(port: FakePort, c: SetupController) {
        c.find()
        c.choose(port.nearby.value.first())
        // The link is collected on the composition's scope, so let it subscribe first.
        waitForIdle()
        port.ready("AA")
        waitForIdle()
    }

    @Test
    fun systemBackAfterPairingAsksBeforeLeavingWhileThePlanLoads() = runComposeUiTest {
        val port = FakePort()
        val run = show(port)
        val c = run.controller!!
        pair(port, c)
        assertEquals(SetupPage.Loading, c.state.value.page, "no page 1 yet, so no plan")
        back.backCompleted()
        waitForIdle()
        assertTrue(c.state.value.askClose)
        assertFalse(c.state.value.closed)
        assertEquals(0, run.closes)
    }

    @Test
    fun systemBackOnTheFirstStepAsksBeforeLeaving() = runComposeUiTest {
        val port = FakePort()
        val run = show(port, LocationSource(LocationConfig(cell = "", resolution = 8, country = "")))
        val c = run.controller!!
        pair(port, c)
        // Page 1 was read before pairing ended, and the plan still reaches the wizard.
        assertEquals(SetupPage.Step(StepId.LOCATION), c.state.value.page)
        back.backCompleted()
        waitForIdle()
        assertTrue(c.state.value.askClose)
        assertEquals(SetupPage.Step(StepId.LOCATION), c.state.value.page)
        assertEquals(0, run.closes)
    }

    @Test
    fun aSensorPickedOnMyDevicesShowsOnFindWhileItPairs() = runComposeUiTest {
        val port = FakePort()
        port.nearby.value = emptyList()
        show(port, pick = SetupCandidate("CC", "Balcony", added = false))
        onNode(hasText("Balcony") and hasClickAction()).assertExists()
        onNode(hasText("Pairing"), useUnmergedTree = true).assertExists()
        // The scan hears the same sensor with its id in lower case, so its row replaces the pick.
        port.nearby.value = listOf(SetupCandidate("cc", "Balcony", added = false))
        waitForIdle()
        onAllNodes(hasText("Balcony") and hasClickAction()).assertCountEquals(1)
        onNode(hasText("Pairing"), useUnmergedTree = true).assertExists()
    }

    @Test
    fun systemBackBeforePairingClosesAtOnce() = runComposeUiTest {
        val run = show(FakePort())
        back.backCompleted()
        waitForIdle()
        assertFalse(run.controller!!.state.value.askClose)
        assertEquals(1, run.closes)
    }

    @Test
    fun aCloseAfterTheHostSwappedItsLambdaCallsTheNewOne() = runComposeUiTest {
        val calls = ArrayList<String>()
        var onClose by mutableStateOf<() -> Unit>({ calls += "first" })
        var controller: SetupController? = null
        setContent {
            CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides owner) {
                MaterialTheme {
                    val scope = rememberCoroutineScope()
                    val c = controller ?: SetupController(scope, FakePort()).also { controller = it }
                    SetupScreen(controller = c, config = null, bluetooth = null, onConfigAction = {}, onClose = onClose, onOpenSensor = {})
                }
            }
        }
        waitForIdle()
        onClose = { calls += "second" }
        waitForIdle()
        back.backCompleted()
        waitForIdle()
        assertEquals(listOf("second"), calls)
    }

    @Test
    fun theToolbarArrowSaysBack() = runComposeUiTest {
        show(FakePort())
        onNodeWithContentDescription("Back").assertHasClickAction()
        onAllNodes(hasContentDescription("Close")).assertCountEquals(0)
    }

    @Test
    fun onlyAConfirmedCellOnTheBoardCountsAsLocated() = runComposeUiTest {
        val port = FakePort()
        // The board answers page 1 with a country and no cell, so the form has a location object.
        val source = LocationSource(LocationConfig(cell = "", resolution = 8, country = "GH"))
        val run = show(port, source)
        val c = run.controller!!
        pair(port, c)
        assertEquals(true, run.config!!.state.value.location.supported)
        assertEquals(false, c.state.value.step?.satisfied)
        assertFalse(c.state.value.canAdvance, "a fixed Thing needs its cell")
        source.location = LocationConfig(cell = "85283083fffffff", resolution = 5, cellResolution = 5, country = "")
        run.config!!.refresh()
        waitForIdle()
        assertEquals(true, c.state.value.step?.satisfied)
        assertTrue(c.state.value.canAdvance)
    }

    @Test
    fun aMovingThingNeedsNoCell() = runComposeUiTest {
        val port = FakePort()
        val source = LocationSource(LocationConfig(cell = "", resolution = 8, country = ""), mobility = "fixed")
        val run = show(port, source)
        val c = run.controller!!
        pair(port, c)
        assertFalse(c.state.value.canAdvance)
        source.mobility = "moving"
        run.config!!.refresh()
        waitForIdle()
        assertTrue(c.state.value.canAdvance, "a moving Thing takes its cell from its own receiver")
    }
}
