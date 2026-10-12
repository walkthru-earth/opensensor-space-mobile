package earth.walkthru.aqsync.ui.components

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.material3.SnackbarHostState
import earth.walkthru.aqsync.R
import earth.walkthru.aqsync.collection.SyncEndReason
import earth.walkthru.aqsync.data.DeviceContinuityPolicy
import earth.walkthru.aqsync.data.DeviceEvent
import earth.walkthru.aqsync.data.DeviceMessage
import earth.walkthru.aqsync.data.DeviceMessage.Command
import earth.walkthru.aqsync.data.DeviceMessage.ConfigChange
import earth.walkthru.aqsync.data.lan.LanFailure
import earth.walkthru.aqsync.data.protocol.ConfigProblem
import earth.walkthru.aqsync.link.UnavailableKind

/**
 * A string resource and its arguments, resolved late so the mapping is a pure, testable function.
 * An argument that is itself a [UiText] is resolved first.
 */
sealed interface UiText {
    val args: List<Any>

    data class Res(@StringRes val id: Int, override val args: List<Any> = emptyList()) : UiText
    data class Plural(@PluralsRes val id: Int, val count: Int, override val args: List<Any> = emptyList()) : UiText

    fun resolve(res: Resources): String {
        val resolved = args.map { if (it is UiText) it.resolve(res) else it }.toTypedArray()
        return when (this) {
            is Res -> res.getString(id, *resolved)
            is Plural -> res.getQuantityString(id, count, *resolved)
        }
    }
}

private fun res(@StringRes id: Int, vararg args: Any) = UiText.Res(id, args.toList())

private fun plural(@PluralsRes id: Int, count: Long, vararg args: Any) =
    UiText.Plural(id, count.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(), args.toList())

/** Every [DeviceMessage] code to its copy in `strings.xml`. */
fun DeviceMessage.toUiText(): UiText = when (this) {
    DeviceMessage.BackgroundOwnsConnect -> res(R.string.msg_background_owns_connect)
    DeviceMessage.BackgroundOwnsSync -> res(R.string.msg_background_owns_sync)
    DeviceMessage.BackgroundOwnsCommand -> res(R.string.msg_background_owns_command)
    DeviceMessage.BusyBeforeSync -> res(R.string.msg_busy_before_sync)
    DeviceMessage.Busy -> res(R.string.msg_busy)
    is DeviceMessage.LanFallback -> res(
        when (failure) {
            LanFailure.AUTH_FAILED -> R.string.msg_lan_token_rejected
            LanFailure.UNREACHABLE -> R.string.msg_lan_unreachable
            LanFailure.TIMEOUT -> R.string.msg_lan_timeout
            LanFailure.BUSY -> R.string.msg_lan_busy
            LanFailure.PROTOCOL, null -> R.string.msg_lan_failed
        },
    )
    DeviceMessage.ConnectFailed -> res(R.string.conn_failed)
    is DeviceMessage.BluetoothUnavailable -> res(
        when (kind) {
            UnavailableKind.RADIO_OFF -> R.string.msg_bluetooth_off
            UnavailableKind.PERMISSION_MISSING -> R.string.msg_bluetooth_permission
            UnavailableKind.NO_ADAPTER -> R.string.msg_bluetooth_missing
        },
    )
    DeviceMessage.PairingRepair -> res(R.string.conn_pairing_repair)
    is DeviceMessage.NewerProtocol -> res(R.string.msg_newer_protocol, device, app)
    is DeviceMessage.FirmwareUpdateRequired ->
        device?.let { res(R.string.msg_firmware_update_required, it, app) } ?: res(R.string.msg_firmware_update_required_unknown, app)
    is DeviceMessage.IdentityRejected -> res(reason.toStringRes())
    DeviceMessage.HardwareInUse -> res(R.string.msg_hardware_in_use)
    DeviceMessage.VerificationFailed -> res(R.string.msg_verification_failed)
    DeviceMessage.StationChanged -> res(R.string.msg_station_changed)
    is DeviceMessage.Reconnecting -> res(R.string.msg_reconnecting, attempt, max)
    is DeviceMessage.ReconnectFailed -> plural(R.plurals.msg_reconnect_failed, attempts.toLong(), attempts)
    DeviceMessage.LanTokenRenewed -> res(R.string.msg_lan_token_renewed)
    DeviceMessage.LanTokenStored -> res(R.string.msg_lan_token_stored)
    DeviceMessage.TokenNeedsBluetooth -> res(R.string.msg_token_needs_ble)
    is DeviceMessage.TokenRotated -> res(
        when (outcome) {
            DeviceMessage.TokenRotation.LAN_SESSION_DROPS -> R.string.msg_token_rotated_lan
            DeviceMessage.TokenRotation.STORED -> R.string.msg_token_rotated_stored
            DeviceMessage.TokenRotation.FETCH_LATER -> R.string.msg_token_rotated_fetch
        },
    )
    DeviceMessage.FileListNotReady -> res(R.string.msg_file_list_not_ready)
    DeviceMessage.NoStationId -> res(R.string.msg_no_station)
    DeviceMessage.UpToDate -> res(R.string.msg_up_to_date)
    is DeviceMessage.FileNotSynced -> res(
        when (problem) {
            DeviceMessage.FileProblem.CHECK_FAILED -> R.string.msg_file_check_failed
            DeviceMessage.FileProblem.DEVICE_REFUSED -> R.string.msg_file_refused
            DeviceMessage.FileProblem.FAILED -> R.string.msg_file_failed
        },
        name,
    )
    is DeviceMessage.SyncFinished -> plural(R.plurals.msg_sync_finished, total.toLong(), synced, total)
    DeviceMessage.SyncStopped -> res(R.string.msg_sync_stopped)
    is DeviceMessage.SyncEnded -> res(reason.toStringRes())
    is DeviceMessage.NotEnoughSpace -> res(R.string.msg_not_enough_space, formatBytes(shortBytes))
    is DeviceMessage.UnplacedReadings -> plural(R.plurals.msg_unplaced, rows, rows)
    DeviceMessage.ClockSet -> res(R.string.msg_clock_set)
    is DeviceMessage.Finalized -> plural(R.plurals.msg_finalized, rows.toLong(), rows, files)
    is DeviceMessage.ConfigSaved -> if (rebootRequired) res(R.string.msg_saved_after_reboot, res(change.toStringRes())) else res(change.toStringRes())
    is DeviceMessage.ConfigKeyRejected -> res(R.string.msg_config_key_rejected, key)
    DeviceMessage.NoRebootWhileSyncing -> res(R.string.msg_no_reboot_syncing)
    DeviceMessage.Rebooting -> res(R.string.msg_rebooting)
    is DeviceMessage.CommandRejected -> res(command.rejectedRes())
    is DeviceMessage.CommandFailed -> res(command.failedRes())
}

/** One line per way a sync can end, shared with the notification and the Files screen. */
@StringRes
fun SyncEndReason.toStringRes(): Int = when (this) {
    SyncEndReason.COMPLETED -> R.string.msg_up_to_date
    SyncEndReason.STOPPED -> R.string.msg_sync_stopped
    SyncEndReason.INTERRUPTED -> R.string.msg_sync_end_interrupted
    SyncEndReason.BLUETOOTH_OFF -> R.string.msg_sync_end_bluetooth_off
    SyncEndReason.PERMISSION_MISSING -> R.string.msg_sync_end_permission
    SyncEndReason.PAIRING_REPAIR -> R.string.conn_pairing_repair
    SyncEndReason.TOKEN_ROTATED -> R.string.msg_sync_end_token_rotated
    SyncEndReason.TOKEN_REJECTED -> R.string.msg_sync_end_token_rejected
    SyncEndReason.DEVICE_BUSY -> R.string.msg_sync_end_busy
    SyncEndReason.OUT_OF_RANGE -> R.string.msg_sync_end_out_of_range
    SyncEndReason.UPDATE_FIRMWARE -> R.string.msg_sync_end_update_firmware
    SyncEndReason.NO_SPACE -> R.string.msg_sync_end_no_space
    SyncEndReason.STORAGE_ERROR -> R.string.msg_sync_end_storage
    SyncEndReason.LINK_LOST -> R.string.msg_sync_end_link_lost
    SyncEndReason.OTHER -> R.string.msg_sync_aborted
}

@StringRes
fun ConfigChange.toStringRes(): Int = when (this) {
    ConfigChange.SETTINGS -> R.string.msg_settings_saved
    ConfigChange.PAIRING -> R.string.msg_pairing_saved
    ConfigChange.PIN -> R.string.msg_pin_saved
    ConfigChange.WIFI_SENT -> R.string.msg_wifi_sent
    ConfigChange.WIFI_ON -> R.string.msg_wifi_on
    ConfigChange.WIFI_OFF -> R.string.msg_wifi_off
    ConfigChange.LAN_ON -> R.string.msg_lan_on
    ConfigChange.LAN_OFF -> R.string.msg_lan_off
    ConfigChange.BONDS_CLEARED -> R.string.msg_bonds_cleared
}

@StringRes
fun DeviceContinuityPolicy.Reason.toStringRes(): Int = when (this) {
    DeviceContinuityPolicy.Reason.ENDPOINT_CHANGED -> R.string.msg_identity_endpoint_changed
    DeviceContinuityPolicy.Reason.HARDWARE_CHANGED -> R.string.msg_identity_hardware_changed
    DeviceContinuityPolicy.Reason.HARDWARE_MISSING -> R.string.msg_identity_hardware_missing
    DeviceContinuityPolicy.Reason.HARDWARE_MALFORMED -> R.string.msg_identity_hardware_malformed
    DeviceContinuityPolicy.Reason.STATION_MISSING -> R.string.msg_identity_station_missing
    DeviceContinuityPolicy.Reason.STATION_MALFORMED -> R.string.msg_identity_station_malformed
    DeviceContinuityPolicy.Reason.ENROLLMENT_REQUIRES_BLE -> R.string.msg_identity_enroll_ble
    DeviceContinuityPolicy.Reason.STATION_CHANGE_REQUIRES_BLE -> R.string.msg_identity_station_ble
    DeviceContinuityPolicy.Reason.STATION_CHANGE_REQUIRES_HARDWARE -> R.string.msg_identity_station_hardware
}

@StringRes
private fun Command.rejectedRes(): Int = when (this) {
    Command.GET_TOKEN -> R.string.msg_rejected_get_token
    Command.SET_TIME -> R.string.msg_rejected_set_time
    Command.FLUSH -> R.string.msg_rejected_flush
    Command.GET_CONFIG -> R.string.msg_rejected_get_config
    Command.SET_CONFIG -> R.string.msg_rejected_set_config
    Command.REBOOT -> R.string.msg_rejected_reboot
    Command.ROTATE_TOKEN -> R.string.msg_rejected_rotate_token
}

@StringRes
private fun Command.failedRes(): Int = when (this) {
    Command.GET_TOKEN -> R.string.msg_failed_get_token
    Command.SET_TIME -> R.string.msg_failed_set_time
    Command.FLUSH -> R.string.msg_failed_flush
    Command.GET_CONFIG -> R.string.msg_failed_get_config
    Command.SET_CONFIG -> R.string.msg_failed_set_config
    Command.REBOOT -> R.string.msg_failed_reboot
    Command.ROTATE_TOKEN -> R.string.msg_failed_rotate_token
}

fun DeviceMessage.text(res: Resources): String = toUiText().resolve(res)

/** The usual snackbar handling of a [DeviceEvent]. */
suspend fun SnackbarHostState.showDeviceEvent(res: Resources, ev: DeviceEvent) {
    when (ev) {
        is DeviceEvent.Info -> showSnackbar(ev.message.text(res))
        is DeviceEvent.Error -> showSnackbar(ev.message.text(res))
    }
}

/** A snackbar line on a screen. Device codes come from the data layer, screen lines are already localized. */
sealed interface ScreenMessage {
    data class Device(val event: DeviceEvent) : ScreenMessage
    data class Text(val text: String, val isError: Boolean = false) : ScreenMessage
}

/** [showDeviceEvent] for screens that also post their own lines. */
suspend fun SnackbarHostState.showScreenMessage(res: Resources, msg: ScreenMessage) {
    when (msg) {
        is ScreenMessage.Device -> showDeviceEvent(res, msg.event)
        is ScreenMessage.Text -> showSnackbar(msg.text)
    }
}

@StringRes
fun ConfigProblem.toStringRes(): Int = when (this) {
    ConfigProblem.PIN_FORMAT -> R.string.config_problem_pin
    ConfigProblem.SSID_EMPTY -> R.string.config_problem_ssid_empty
    ConfigProblem.SSID_TOO_LONG -> R.string.config_problem_ssid_long
    ConfigProblem.PSK_TOO_LONG -> R.string.config_problem_psk_long
    ConfigProblem.STICKER_FORMAT -> R.string.msg_sticker_invalid
    ConfigProblem.LINE_BREAK -> R.string.config_problem_line_break
}
