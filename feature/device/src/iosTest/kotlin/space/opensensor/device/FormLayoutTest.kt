package space.opensensor.device

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import space.opensensor.data.WifiScanState
import space.opensensor.data.ble.ConnectionState
import space.opensensor.data.ble.TransportKind
import space.opensensor.data.protocol.DeviceInfo
import space.opensensor.data.protocol.Frame
import space.opensensor.data.protocol.PairMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Device settings forms stay easy to hit and read out (#118). Each pairing choice is a 48 dp row
 * with a gap to the next, the Wi-Fi switch is one labelled switch node, and an over long
 * passphrase says why Save is off. Rendered headless on the iOS simulator.
 */
@OptIn(ExperimentalTestApi::class)
class FormLayoutTest {
    private val state = DeviceConfigUiState(
        DeviceConfigSnapshot(
            address = "AA",
            connection = ConnectionState.Ready(247),
            transport = TransportKind.BLE,
            info = DeviceInfo(proto = 3, schema = "waveshare-sim7670g-telemetry-v4"),
            wifiScan = WifiScanState.Done(listOf(Frame.WifiAp(rssi = -50, auth = 3, channel = 6, ssid = "home")), atEpochMs = 1),
        ),
    )

    private val isSwitch = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)

    @Test
    fun pairingChoicesAreTallRowsWithAGapBetweenThem() = runComposeUiTest {
        setContent { MaterialTheme { PairingCard(state, onSave = {}, onReboot = {}) } }
        val rows = PairMode.entries.map { onNodeWithTag("pair-mode-${it.name}") }
        rows.forEach { it.assertHeightIsAtLeast(48.dp) }
        rows.zipWithNext().forEach { (upper, lower) ->
            val gap = lower.getUnclippedBoundsInRoot().top - upper.getUnclippedBoundsInRoot().bottom
            assertTrue(gap >= 8.dp, "gap between pairing choices is $gap")
        }
    }

    @Test
    fun theWifiSwitchIsOneLabelledSwitch() = runComposeUiTest {
        setContent { MaterialTheme { WifiCard(state, onToggleWifi = {}, onScan = {}, onClearScan = {}, onJoin = {}) } }
        assertEquals(1, onAllNodes(isSwitch).fetchSemanticsNodes().size)
        onNode(isSwitch and hasText("Sensor Wi-Fi")).assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun anOverLongPassphraseSaysWhySaveIsOff() = runComposeUiTest {
        setContent { MaterialTheme { WifiCard(state, onToggleWifi = {}, onScan = {}, onClearScan = {}, onJoin = {}) } }
        onNodeWithText("home").performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        onNode(hasSetTextAction()).performTextInput("a".repeat(64))
        onNodeWithText("At most 63 characters").assertExists()
        onNodeWithText("Save and connect").assertIsNotEnabled()
    }
}
