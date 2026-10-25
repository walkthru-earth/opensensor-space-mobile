package earth.walkthru.aqsync.ui.device

import earth.walkthru.aqsync.data.DeviceEvent
import earth.walkthru.aqsync.data.DeviceMessage
import earth.walkthru.aqsync.ui.components.ScreenMessage
import earth.walkthru.opensensor.device.DeviceConfigNotice
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

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
