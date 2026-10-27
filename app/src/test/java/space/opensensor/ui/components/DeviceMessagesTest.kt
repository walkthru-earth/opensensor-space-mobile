package space.opensensor.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test
import space.opensensor.app.R
import space.opensensor.collection.SyncEndReason
import space.opensensor.data.DeviceContinuityPolicy
import space.opensensor.data.DeviceMessage
import space.opensensor.data.DeviceMessage.Command
import space.opensensor.data.DeviceMessage.ConfigChange
import space.opensensor.data.lan.LanFailure
import space.opensensor.data.protocol.ConfigProblem
import space.opensensor.link.UnavailableKind

/** Device codes from the data layer reach the screen as string resources with typed arguments. */
class DeviceMessagesTest {
    @Test
    fun `codes map to their resources and arguments`() {
        assertEquals(UiText.Res(R.string.msg_busy), DeviceMessage.Busy.toUiText())
        assertEquals(UiText.Res(R.string.msg_reconnecting, listOf(2, 3)), DeviceMessage.Reconnecting(2, 3).toUiText())
        assertEquals(UiText.Res(R.string.msg_newer_protocol, listOf(3, 2)), DeviceMessage.NewerProtocol(device = 3, app = 2).toUiText())
        assertEquals(UiText.Res(R.string.msg_firmware_update_required, listOf(2, 3)), DeviceMessage.FirmwareUpdateRequired(device = 2, app = 3).toUiText())
        assertEquals(UiText.Res(R.string.msg_firmware_update_required_unknown, listOf(3)), DeviceMessage.FirmwareUpdateRequired(device = null, app = 3).toUiText())
        assertEquals(UiText.Plural(R.plurals.msg_sync_finished, 4, listOf(3, 4)), DeviceMessage.SyncFinished(synced = 3, total = 4).toUiText())
        assertEquals(UiText.Plural(R.plurals.msg_unplaced, 12, listOf(12L)), DeviceMessage.UnplacedReadings(12).toUiText())
        assertEquals(UiText.Plural(R.plurals.msg_finalized, 5, listOf<Any>(5, 2L)), DeviceMessage.Finalized(rows = 5, files = 2).toUiText())
        // A refusal names the card in words and never shows the wire key.
        assertEquals(UiText.Res(R.string.msg_config_rejected_wifi), DeviceMessage.ConfigKeyRejected("wifi.ssid").toUiText())
        assertEquals(UiText.Res(R.string.msg_config_rejected_pairing), DeviceMessage.ConfigKeyRejected("ble.pin").toUiText())
        assertEquals(UiText.Res(R.string.msg_config_rejected_lan), DeviceMessage.ConfigKeyRejected("lan.on").toUiText())
        assertEquals(UiText.Res(R.string.msg_config_rejected_location), DeviceMessage.ConfigKeyRejected("location.cell").toUiText())
        assertEquals(UiText.Res(R.string.msg_config_rejected_sensor), DeviceMessage.ConfigKeyRejected("sensor.serial").toUiText())
        assertEquals(UiText.Res(R.string.msg_config_rejected_other), DeviceMessage.ConfigKeyRejected("debug.level").toUiText())
        assertEquals(
            UiText.Res(R.string.msg_file_check_failed, listOf("data_0900.parquet")),
            DeviceMessage.FileNotSynced("data_0900.parquet", DeviceMessage.FileProblem.CHECK_FAILED).toUiText(),
        )
    }

    @Test
    fun `a byte count uses the same units as Files and rolls over after rounding`() {
        assertEquals(
            UiText.Res(R.string.msg_not_enough_space, listOf(UiText.Res(R.string.unit_megabytes, listOf("1.0")))),
            DeviceMessage.NotEnoughSpace(1_048_575).toUiText(),
        )
        assertEquals(UiText.Res(R.string.unit_kilobytes, listOf("1.5")), bytesText(1536))
        assertEquals(UiText.Res(R.string.unit_bytes, listOf("512")), bytesText(512))
    }

    @Test
    fun `a saved setting names the change and nests it when a restart is needed`() {
        assertEquals(UiText.Res(R.string.msg_pin_saved), DeviceMessage.ConfigSaved(ConfigChange.PIN).toUiText())
        assertEquals(
            UiText.Res(R.string.msg_saved_after_reboot, listOf(UiText.Res(R.string.msg_pairing_saved))),
            DeviceMessage.ConfigSaved(ConfigChange.PAIRING, rebootRequired = true).toUiText(),
        )
    }

    @Test
    fun `LAN fallback names the failure and an untyped one stays generic`() {
        assertEquals(UiText.Res(R.string.msg_lan_token_rejected), DeviceMessage.LanFallback(LanFailure.AUTH_FAILED).toUiText())
        assertEquals(UiText.Res(R.string.msg_lan_failed), DeviceMessage.LanFallback(null).toUiText())
        assertEquals(UiText.Res(R.string.msg_lan_failed), DeviceMessage.LanFallback(LanFailure.PROTOCOL).toUiText())
    }

    @Test
    fun `every reason command and change has its own copy`() {
        assertDistinct(DeviceContinuityPolicy.Reason.entries.map { DeviceMessage.IdentityRejected(it).toUiText() })
        assertDistinct(Command.entries.map { DeviceMessage.CommandRejected(it).toUiText() })
        assertDistinct(Command.entries.map { DeviceMessage.CommandFailed(it).toUiText() })
        assertDistinct(ConfigChange.entries.map { it.toStringRes() })
        assertDistinct(ConfigProblem.entries.map { it.toStringRes() })
        assertDistinct(DeviceMessage.TokenRotation.entries.map { DeviceMessage.TokenRotated(it).toUiText() })
        assertDistinct(SyncEndReason.entries.map { it.toStringRes() })
        assertDistinct(UnavailableKind.entries.map { DeviceMessage.BluetoothUnavailable(it).toUiText() })
    }

    @Test
    fun `a sync ending names its reason and shares the pairing and stop lines`() {
        assertEquals(UiText.Res(R.string.msg_sync_end_bluetooth_off), DeviceMessage.SyncEnded(SyncEndReason.BLUETOOTH_OFF).toUiText())
        assertEquals(UiText.Res(R.string.msg_sync_end_token_rotated), DeviceMessage.SyncEnded(SyncEndReason.TOKEN_ROTATED).toUiText())
        assertEquals(UiText.Res(R.string.conn_pairing_repair), DeviceMessage.SyncEnded(SyncEndReason.PAIRING_REPAIR).toUiText())
        assertEquals(UiText.Res(R.string.msg_sync_stopped), DeviceMessage.SyncEnded(SyncEndReason.STOPPED).toUiText())
        assertEquals(UiText.Res(R.string.msg_sync_aborted), DeviceMessage.SyncEnded(SyncEndReason.OTHER).toUiText())
    }

    private fun assertDistinct(items: List<Any>) = assertEquals(items.size, items.toSet().size)
}
