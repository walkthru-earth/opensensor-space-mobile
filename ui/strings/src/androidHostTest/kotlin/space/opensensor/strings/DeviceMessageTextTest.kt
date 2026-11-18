package space.opensensor.strings

import space.opensensor.collection.SyncEndReason
import space.opensensor.data.DeviceContinuityPolicy
import space.opensensor.data.DeviceMessage
import space.opensensor.data.DeviceMessage.Command
import space.opensensor.data.DeviceMessage.ConfigChange
import space.opensensor.link.UnavailableKind
import space.opensensor.strings.resources.Res
import space.opensensor.strings.resources.conn_pairing_repair
import space.opensensor.strings.resources.msg_busy
import space.opensensor.strings.resources.msg_config_rejected_lan
import space.opensensor.strings.resources.msg_config_rejected_location
import space.opensensor.strings.resources.msg_config_rejected_other
import space.opensensor.strings.resources.msg_config_rejected_pairing
import space.opensensor.strings.resources.msg_config_rejected_sensor
import space.opensensor.strings.resources.msg_config_rejected_wifi
import space.opensensor.strings.resources.msg_file_check_failed
import space.opensensor.strings.resources.msg_finalized
import space.opensensor.strings.resources.msg_firmware_update_required
import space.opensensor.strings.resources.msg_firmware_update_required_unknown
import space.opensensor.strings.resources.msg_newer_protocol
import space.opensensor.strings.resources.msg_not_enough_space
import space.opensensor.strings.resources.msg_pairing_saved
import space.opensensor.strings.resources.msg_pin_saved
import space.opensensor.strings.resources.msg_reconnecting
import space.opensensor.strings.resources.msg_saved_after_reboot
import space.opensensor.strings.resources.msg_sync_aborted
import space.opensensor.strings.resources.msg_sync_end_bluetooth_off
import space.opensensor.strings.resources.msg_sync_end_wifi_access
import space.opensensor.strings.resources.msg_sync_finished
import space.opensensor.strings.resources.msg_sync_stopped
import space.opensensor.strings.resources.msg_unplaced
import space.opensensor.strings.resources.unit_bytes
import space.opensensor.strings.resources.unit_kilobytes
import space.opensensor.strings.resources.unit_megabytes
import kotlin.test.Test
import kotlin.test.assertEquals

/** Device codes reach both phones as catalogue strings with typed arguments, from one mapping. */
class DeviceMessageTextTest {
    @Test
    fun `codes map to their resources and arguments`() {
        assertEquals(UiText.Resource(Res.string.msg_busy), DeviceMessage.Busy.text())
        assertEquals(UiText.Resource(Res.string.msg_reconnecting, listOf(2, 3)), DeviceMessage.Reconnecting(2, 3).text())
        assertEquals(UiText.Resource(Res.string.msg_newer_protocol, listOf(3, 2)), DeviceMessage.NewerProtocol(device = 3, app = 2).text())
        assertEquals(UiText.Resource(Res.string.msg_firmware_update_required, listOf(2, 3)), DeviceMessage.FirmwareUpdateRequired(device = 2, app = 3).text())
        assertEquals(UiText.Resource(Res.string.msg_firmware_update_required_unknown, listOf(3)), DeviceMessage.FirmwareUpdateRequired(device = null, app = 3).text())
        assertEquals(UiText.Plural(Res.plurals.msg_sync_finished, 4, listOf(3, 4)), DeviceMessage.SyncFinished(synced = 3, total = 4).text())
        assertEquals(UiText.Plural(Res.plurals.msg_unplaced, 12, listOf(12L)), DeviceMessage.UnplacedReadings(12).text())
        assertEquals(UiText.Plural(Res.plurals.msg_finalized, 5, listOf<Any>(5, 2L)), DeviceMessage.Finalized(rows = 5, files = 2).text())
        // A refusal names the card in words and never shows the wire key.
        assertEquals(UiText.Resource(Res.string.msg_config_rejected_wifi), DeviceMessage.ConfigKeyRejected("wifi.ssid").text())
        assertEquals(UiText.Resource(Res.string.msg_config_rejected_pairing), DeviceMessage.ConfigKeyRejected("ble.pin").text())
        assertEquals(UiText.Resource(Res.string.msg_config_rejected_lan), DeviceMessage.ConfigKeyRejected("lan.on").text())
        assertEquals(UiText.Resource(Res.string.msg_config_rejected_location), DeviceMessage.ConfigKeyRejected("location.cell").text())
        assertEquals(UiText.Resource(Res.string.msg_config_rejected_sensor), DeviceMessage.ConfigKeyRejected("sensor.serial").text())
        assertEquals(UiText.Resource(Res.string.msg_config_rejected_other), DeviceMessage.ConfigKeyRejected("debug.level").text())
        assertEquals(
            UiText.Resource(Res.string.msg_file_check_failed, listOf("data_0900.parquet")),
            DeviceMessage.FileNotSynced("data_0900.parquet", DeviceMessage.FileProblem.CHECK_FAILED).text(),
        )
    }

    @Test
    fun `a byte count uses the same units as Files and rolls over after rounding`() {
        assertEquals(
            UiText.Resource(Res.string.msg_not_enough_space, listOf(UiText.Resource(Res.string.unit_megabytes, listOf("1.0")))),
            DeviceMessage.NotEnoughSpace(1_048_575).text(),
        )
        assertEquals(UiText.Resource(Res.string.unit_kilobytes, listOf("1.5")), bytesText(1536))
        assertEquals(UiText.Resource(Res.string.unit_bytes, listOf("512")), bytesText(512))
    }

    @Test
    fun `a saved setting names the change and nests it when a restart is needed`() {
        assertEquals(UiText.Resource(Res.string.msg_pin_saved), DeviceMessage.ConfigSaved(ConfigChange.PIN).text())
        assertEquals(
            UiText.Resource(Res.string.msg_saved_after_reboot, listOf(UiText.Resource(Res.string.msg_pairing_saved))),
            DeviceMessage.ConfigSaved(ConfigChange.PAIRING, rebootRequired = true).text(),
        )
    }

    @Test
    fun `every reason command and change has its own copy`() {
        assertDistinct(DeviceContinuityPolicy.Reason.entries.map { DeviceMessage.IdentityRejected(it).text() })
        assertDistinct(Command.entries.map { DeviceMessage.CommandRejected(it).text() })
        assertDistinct(Command.entries.map { DeviceMessage.CommandFailed(it).text() })
        assertDistinct(ConfigChange.entries.map { DeviceMessage.ConfigSaved(it).text() })
        assertDistinct(DeviceMessage.TokenRotation.entries.map { DeviceMessage.TokenRotated(it).text() })
        // A rotated and a rejected token share one line, both renew on the next Bluetooth connection.
        assertDistinct((SyncEndReason.entries - SyncEndReason.TOKEN_REJECTED).map { it.text() })
        assertDistinct(UnavailableKind.entries.map { DeviceMessage.BluetoothUnavailable(it).text() })
    }

    @Test
    fun `a sync ending names its reason and shares the pairing and stop lines`() {
        assertEquals(UiText.Resource(Res.string.msg_sync_end_bluetooth_off), DeviceMessage.SyncEnded(SyncEndReason.BLUETOOTH_OFF).text())
        assertEquals(UiText.Resource(Res.string.msg_sync_end_wifi_access), DeviceMessage.SyncEnded(SyncEndReason.TOKEN_ROTATED).text())
        assertEquals(UiText.Resource(Res.string.msg_sync_end_wifi_access), DeviceMessage.SyncEnded(SyncEndReason.TOKEN_REJECTED).text())
        assertEquals(UiText.Resource(Res.string.conn_pairing_repair), DeviceMessage.SyncEnded(SyncEndReason.PAIRING_REPAIR).text())
        assertEquals(UiText.Resource(Res.string.msg_sync_stopped), DeviceMessage.SyncEnded(SyncEndReason.STOPPED).text())
        assertEquals(UiText.Resource(Res.string.msg_sync_aborted), DeviceMessage.SyncEnded(SyncEndReason.OTHER).text())
    }

    private fun assertDistinct(items: List<Any>) = assertEquals(items.size, items.toSet().size)
}
