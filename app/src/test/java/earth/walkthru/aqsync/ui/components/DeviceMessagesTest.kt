package earth.walkthru.aqsync.ui.components

import earth.walkthru.aqsync.R
import earth.walkthru.aqsync.data.DeviceContinuityPolicy
import earth.walkthru.aqsync.data.DeviceMessage
import earth.walkthru.aqsync.data.DeviceMessage.Command
import earth.walkthru.aqsync.data.DeviceMessage.ConfigChange
import earth.walkthru.aqsync.data.lan.LanFailure
import earth.walkthru.aqsync.data.protocol.ConfigProblem
import org.junit.Assert.assertEquals
import org.junit.Test

/** Device codes from the data layer reach the screen as string resources with typed arguments. */
class DeviceMessagesTest {
    @Test
    fun `codes map to their resources and arguments`() {
        assertEquals(UiText.Res(R.string.msg_busy), DeviceMessage.Busy.toUiText())
        assertEquals(UiText.Res(R.string.msg_reconnecting, listOf(2, 3)), DeviceMessage.Reconnecting(2, 3).toUiText())
        assertEquals(UiText.Res(R.string.msg_newer_protocol, listOf(3, 2)), DeviceMessage.NewerProtocol(device = 3, app = 2).toUiText())
        assertEquals(UiText.Plural(R.plurals.msg_sync_finished, 4, listOf(3, 4)), DeviceMessage.SyncFinished(synced = 3, total = 4).toUiText())
        assertEquals(UiText.Plural(R.plurals.msg_unplaced, 12, listOf(12L)), DeviceMessage.UnplacedReadings(12).toUiText())
        assertEquals(UiText.Plural(R.plurals.msg_finalized, 5, listOf<Any>(5, 2L)), DeviceMessage.Finalized(rows = 5, files = 2).toUiText())
        assertEquals(UiText.Res(R.string.msg_config_key_rejected, listOf("wifi.ssid")), DeviceMessage.ConfigKeyRejected("wifi.ssid").toUiText())
        assertEquals(
            UiText.Res(R.string.msg_file_check_failed, listOf("data_0900.parquet")),
            DeviceMessage.FileNotSynced("data_0900.parquet", DeviceMessage.FileProblem.CHECK_FAILED).toUiText(),
        )
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
    }

    private fun assertDistinct(items: List<Any>) = assertEquals(items.size, items.toSet().size)
}
