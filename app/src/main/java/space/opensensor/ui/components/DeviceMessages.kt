package space.opensensor.ui.components

import androidx.compose.material3.SnackbarHostState
import space.opensensor.data.DeviceEvent
import space.opensensor.strings.load
import space.opensensor.strings.text

/** The usual snackbar handling of a [DeviceEvent], in the words `:ui:strings` gives both phones. */
suspend fun SnackbarHostState.showDeviceEvent(ev: DeviceEvent) {
    when (ev) {
        is DeviceEvent.Info -> showSnackbar(ev.message.text().load())
        is DeviceEvent.Error -> showSnackbar(ev.message.text().load())
    }
}

/** A snackbar line on a screen. Device codes come from the data layer, screen lines are already localized. */
sealed interface ScreenMessage {
    data class Device(val event: DeviceEvent) : ScreenMessage
    data class Text(val text: String, val isError: Boolean = false) : ScreenMessage
}

/** [showDeviceEvent] for screens that also post their own lines. */
suspend fun SnackbarHostState.showScreenMessage(msg: ScreenMessage) {
    when (msg) {
        is ScreenMessage.Device -> showDeviceEvent(msg.event)
        is ScreenMessage.Text -> showSnackbar(msg.text)
    }
}
