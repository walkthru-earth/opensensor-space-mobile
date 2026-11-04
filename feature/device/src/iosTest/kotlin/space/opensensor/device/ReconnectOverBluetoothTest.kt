package space.opensensor.device

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.jetbrains.compose.resources.stringResource
import space.opensensor.data.ble.ConnectionState
import space.opensensor.data.ble.TransportKind
import space.opensensor.data.protocol.DeviceInfo
import space.opensensor.device.resources.Res
import space.opensensor.device.resources.lan_reconnect_ble
import kotlin.test.Test
import kotlin.test.assertEquals

/** Setup's Wi-Fi step offers the way back to Bluetooth only on a ready link that runs on Wi-Fi. */
@OptIn(ExperimentalTestApi::class)
class ReconnectOverBluetoothTest {
    private fun state(transport: TransportKind?, connection: ConnectionState = ConnectionState.Ready(247)) = DeviceConfigUiState(
        DeviceConfigSnapshot(
            address = "AA",
            connection = connection,
            transport = transport,
            info = DeviceInfo(proto = 3, schema = "waveshare-sim7670g-telemetry-v4"),
        ),
    )

    private fun render(state: DeviceConfigUiState, expected: Int) = runComposeUiTest {
        var label = ""
        var taps = 0
        setContent {
            MaterialTheme {
                label = stringResource(Res.string.lan_reconnect_ble)
                ReconnectOverBluetooth(state, onReconnectBle = { taps++ })
            }
        }
        onAllNodesWithText(label).assertCountEquals(expected)
        if (expected == 1) {
            onNodeWithText(label).performClick()
            assertEquals(1, taps)
        }
    }

    @Test
    fun aWifiLinkOffersBluetooth() = render(state(TransportKind.LAN), expected = 1)

    @Test
    fun aBluetoothLinkShowsNothing() = render(state(TransportKind.BLE), expected = 0)

    @Test
    fun noLinkShowsNothing() = render(state(transport = null, connection = ConnectionState.Disconnected), expected = 0)
}
