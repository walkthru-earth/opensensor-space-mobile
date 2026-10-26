package space.opensensor.ui.device

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import space.opensensor.data.DeviceEvent
import space.opensensor.data.DeviceMessage
import space.opensensor.device.DeviceConfigNotice
import space.opensensor.ui.components.ScreenMessage

/**
 * A secret refused on LAN (#169) leaves the Device page snackbar and reaches the shared controller,
 * whose snackbar offers the Bluetooth reconnect. Every other repository event stays on the page.
 */
class DeviceEventRoutingTest {
    private val events = flowOf(
        DeviceEvent.Error(DeviceMessage.Busy),
        DeviceEvent.Error(DeviceMessage.SecretNeedsBluetooth),
        DeviceEvent.Info(DeviceMessage.UpToDate),
    )

    @Test
    fun `the page snackbar skips the refused secret`() = runTest {
        assertEquals(
            listOf(
                ScreenMessage.Device(DeviceEvent.Error(DeviceMessage.Busy)),
                ScreenMessage.Device(DeviceEvent.Info(DeviceMessage.UpToDate)),
            ),
            events.screenMessages().toList(),
        )
    }

    @Test
    fun `the refused secret becomes the controller notice`() = runTest {
        assertEquals(listOf(DeviceConfigNotice.SecretNeedsBluetooth), events.configNotices().toList())
    }
}
