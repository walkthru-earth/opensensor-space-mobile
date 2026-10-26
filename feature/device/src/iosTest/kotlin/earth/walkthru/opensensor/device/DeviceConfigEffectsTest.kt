package earth.walkthru.opensensor.device

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runComposeUiTest
import earth.walkthru.aqsync.data.ble.ConfigSnapshot
import earth.walkthru.aqsync.data.protocol.ConfigPatch
import earth.walkthru.aqsync.settings.DetailLevel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A secret refused on LAN (#169) shows its snackbar with the Bluetooth reconnect, and the action
 * runs the reconnect. Other notices show their line alone. Rendered headless on the iOS simulator.
 */
@OptIn(ExperimentalTestApi::class)
class DeviceConfigEffectsTest {
    /** Only the notices matter here. Nothing in these tests sends a command. */
    private class NoticeSource : DeviceConfigSource {
        override val snapshot = MutableStateFlow(DeviceConfigSnapshot(address = "AA"))
        override val notices = MutableSharedFlow<DeviceConfigNotice>(extraBufferCapacity = 8)
        override fun refreshConfig() = Unit
        override fun applyConfig(patch: ConfigPatch, change: DeviceConfigChange) = Unit
        override fun reboot() = Unit
        override fun scanWifi() = Unit
        override fun clearWifiScan() = Unit
        override fun fetchToken() = Unit
        override fun rotateToken() = Unit
        override fun clearBonds() = Unit
        override suspend fun applySensorConfig(patch: ConfigPatch): ConfigSnapshot = error("not sent here")
        override suspend fun recordSensor(serial: String, vendor: String, model: String, batch: String, source: String) = Unit
        override fun reconnect() = Unit
        override fun reconnectOverBluetooth() = Unit
    }

    private val source = NoticeSource()
    private var reconnects = 0

    private fun ComposeUiTest.showEffects() {
        setContent {
            val scope = rememberCoroutineScope()
            val controller = remember { DeviceConfigController(scope, source, flowOf(DetailLevel.SIMPLE)) }
            val snackbar = remember { SnackbarHostState() }
            MaterialTheme {
                DeviceConfigEffects(controller, snackbar) { reconnects++ }
                SnackbarHost(snackbar)
            }
        }
        waitForIdle()
    }

    /**
     * Sends [notice] and waits until [text] shows. The snackbar text comes from Compose resources,
     * and their first load runs in real time, off the test clock. On a slow CI runner that took
     * longer than a fixed number of clock steps, so this waits on the wall clock instead, up to
     * [SHOW_TIMEOUT_MS]. A short snackbar stays for 4 s of test clock, long enough to find it.
     */
    private fun ComposeUiTest.show(notice: DeviceConfigNotice, text: String) {
        // The notice is not replayed, so a send before the controller collects would be lost.
        waitUntil("the controller collects notices", SHOW_TIMEOUT_MS) { source.notices.subscriptionCount.value > 0 }
        source.notices.tryEmit(notice)
        waitUntil("a snackbar reads \"$text\"", SHOW_TIMEOUT_MS) {
            onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun aRefusedSecretOffersTheBluetoothReconnect() = runComposeUiTest {
        showEffects()
        show(DeviceConfigNotice.SecretNeedsBluetooth, "Passwords and PINs go over Bluetooth only.")
        assertEquals(0, reconnects)

        onNodeWithText("Reconnect over Bluetooth").performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        assertEquals(1, reconnects)
    }

    @Test
    fun anotherNoticeShowsItsLineAlone() = runComposeUiTest {
        showEffects()
        show(DeviceConfigNotice.Busy, "Wait for the current sensor task to finish.")
        assertTrue(onAllNodesWithText("Reconnect over Bluetooth").fetchSemanticsNodes().isEmpty())
        assertEquals(0, reconnects)
    }

    private companion object {
        /** Wall clock time for one wait. Locally the first snackbar shows within a few frames. */
        const val SHOW_TIMEOUT_MS = 10_000L
    }
}
