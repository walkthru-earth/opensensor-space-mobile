package space.opensensor.setup

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
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
import space.opensensor.data.protocol.DeviceInfo
import space.opensensor.data.protocol.LocationConfig
import space.opensensor.device.DeviceConfigChange
import space.opensensor.device.DeviceConfigController
import space.opensensor.device.DeviceConfigNotice
import space.opensensor.device.DeviceConfigSnapshot
import space.opensensor.device.DeviceConfigSource
import space.opensensor.settings.DetailLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A ready proto 3 board whose page 1 answers with [location]. Nothing else is called. */
private class LocationSource(var location: LocationConfig) : DeviceConfigSource {
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
    override suspend fun readLocation(): LocationConfig = location
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

/** System back and the saved location, through the real screen and its Navigation Event handler. */
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

    private fun ComposeUiTest.show(port: FakePort, source: LocationSource? = null): Run {
        val run = Run()
        setContent {
            CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides owner) {
                MaterialTheme { Screen(run, port, source) }
            }
        }
        waitForIdle()
        return run
    }

    @Composable
    private fun Screen(run: Run, port: FakePort, source: LocationSource?) {
        val scope = rememberCoroutineScope()
        val c = run.controller ?: SetupController(scope, port).also { run.controller = it }
        val config = source?.let { s -> run.config ?: DeviceConfigController(scope, s, MutableStateFlow(DetailLevel.SIMPLE)).also { run.config = it } }
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
        assertEquals(SetupStep.Name, c.state.value.page)
    }

    @Test
    fun systemBackAfterPairingAsksBeforeLeavingOnEveryPage() = runComposeUiTest {
        val port = FakePort()
        val run = show(port)
        val c = run.controller!!
        pair(port, c)
        back.backCompleted()
        waitForIdle()
        assertTrue(c.state.value.askClose)
        assertFalse(c.state.value.closed)
        c.dismissClose()
        c.next()
        waitForIdle()
        assertEquals(SetupFlows.LOCATION, c.state.value.page)
        back.backCompleted()
        waitForIdle()
        assertTrue(c.state.value.askClose)
        assertEquals(SetupFlows.LOCATION, c.state.value.page)
        assertEquals(0, run.closes)
        assertEquals(setOf("AA"), port.pending)
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
    fun onlyAConfirmedCellOnTheBoardCountsAsLocated() = runComposeUiTest {
        val port = FakePort()
        // The board answers page 1 with a country and no cell, so the form has a location object.
        val source = LocationSource(LocationConfig(cell = "", resolution = 8, country = "GH"))
        val run = show(port, source)
        val c = run.controller!!
        waitForIdle()
        assertEquals(true, run.config!!.state.value.location.supported)
        assertFalse(c.state.value.located)
        source.location = LocationConfig(cell = "85283083fffffff", resolution = 5, cellResolution = 5, country = "")
        run.config!!.refresh()
        waitForIdle()
        assertTrue(c.state.value.located)
    }
}
