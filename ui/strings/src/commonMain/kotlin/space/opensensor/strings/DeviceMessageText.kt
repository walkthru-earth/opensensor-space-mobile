package space.opensensor.strings

import org.jetbrains.compose.resources.StringResource
import space.opensensor.collection.SyncEndReason
import space.opensensor.data.DeviceContinuityPolicy
import space.opensensor.data.DeviceMessage
import space.opensensor.data.DeviceMessage.Command
import space.opensensor.data.DeviceMessage.ConfigChange
import space.opensensor.data.protocol.ConfigFields
import space.opensensor.data.protocol.ConfigGroup
import space.opensensor.link.UnavailableKind
import space.opensensor.strings.resources.Res
import space.opensensor.strings.resources.conn_failed
import space.opensensor.strings.resources.conn_pairing_repair
import space.opensensor.strings.resources.msg_background_owns_command
import space.opensensor.strings.resources.msg_background_owns_connect
import space.opensensor.strings.resources.msg_background_owns_sync
import space.opensensor.strings.resources.msg_bluetooth_missing
import space.opensensor.strings.resources.msg_bluetooth_off
import space.opensensor.strings.resources.msg_bluetooth_permission
import space.opensensor.strings.resources.msg_bonds_cleared
import space.opensensor.strings.resources.msg_busy
import space.opensensor.strings.resources.msg_busy_before_sync
import space.opensensor.strings.resources.msg_config_rejected_lan
import space.opensensor.strings.resources.msg_config_rejected_location
import space.opensensor.strings.resources.msg_config_rejected_other
import space.opensensor.strings.resources.msg_config_rejected_pairing
import space.opensensor.strings.resources.msg_config_rejected_sensor
import space.opensensor.strings.resources.msg_config_rejected_site
import space.opensensor.strings.resources.msg_config_rejected_wifi
import space.opensensor.strings.resources.msg_failed_flush
import space.opensensor.strings.resources.msg_failed_get_config
import space.opensensor.strings.resources.msg_failed_get_token
import space.opensensor.strings.resources.msg_failed_reboot
import space.opensensor.strings.resources.msg_failed_rotate_token
import space.opensensor.strings.resources.msg_failed_set_config
import space.opensensor.strings.resources.msg_file_check_failed
import space.opensensor.strings.resources.msg_file_failed
import space.opensensor.strings.resources.msg_file_list_not_ready
import space.opensensor.strings.resources.msg_file_refused
import space.opensensor.strings.resources.msg_finalized
import space.opensensor.strings.resources.msg_firmware_update_required
import space.opensensor.strings.resources.msg_firmware_update_required_unknown
import space.opensensor.strings.resources.msg_hardware_in_use
import space.opensensor.strings.resources.msg_identity_endpoint_changed
import space.opensensor.strings.resources.msg_identity_enroll_ble
import space.opensensor.strings.resources.msg_identity_hardware_changed
import space.opensensor.strings.resources.msg_identity_hardware_malformed
import space.opensensor.strings.resources.msg_identity_hardware_missing
import space.opensensor.strings.resources.msg_identity_thing_ble
import space.opensensor.strings.resources.msg_identity_thing_hardware
import space.opensensor.strings.resources.msg_identity_thing_malformed
import space.opensensor.strings.resources.msg_identity_thing_missing
import space.opensensor.strings.resources.msg_lan_off
import space.opensensor.strings.resources.msg_lan_on
import space.opensensor.strings.resources.msg_lan_token_stored
import space.opensensor.strings.resources.msg_newer_protocol
import space.opensensor.strings.resources.msg_no_reboot_syncing
import space.opensensor.strings.resources.msg_no_thing
import space.opensensor.strings.resources.msg_not_enough_space
import space.opensensor.strings.resources.msg_pairing_saved
import space.opensensor.strings.resources.msg_pin_saved
import space.opensensor.strings.resources.msg_rebooting
import space.opensensor.strings.resources.msg_reconnect_failed
import space.opensensor.strings.resources.msg_reconnecting
import space.opensensor.strings.resources.msg_rejected_flush
import space.opensensor.strings.resources.msg_rejected_get_config
import space.opensensor.strings.resources.msg_rejected_get_token
import space.opensensor.strings.resources.msg_rejected_reboot
import space.opensensor.strings.resources.msg_rejected_rotate_token
import space.opensensor.strings.resources.msg_rejected_set_config
import space.opensensor.strings.resources.msg_saved_after_reboot
import space.opensensor.strings.resources.msg_secret_needs_bluetooth
import space.opensensor.strings.resources.msg_settings_saved
import space.opensensor.strings.resources.msg_sync_aborted
import space.opensensor.strings.resources.msg_sync_end_bluetooth_off
import space.opensensor.strings.resources.msg_sync_end_busy
import space.opensensor.strings.resources.msg_sync_end_interrupted
import space.opensensor.strings.resources.msg_sync_end_link_lost
import space.opensensor.strings.resources.msg_sync_end_no_space
import space.opensensor.strings.resources.msg_sync_end_out_of_range
import space.opensensor.strings.resources.msg_sync_end_permission
import space.opensensor.strings.resources.msg_sync_end_storage
import space.opensensor.strings.resources.msg_sync_end_update_firmware
import space.opensensor.strings.resources.msg_sync_end_wifi_access
import space.opensensor.strings.resources.msg_sync_finished
import space.opensensor.strings.resources.msg_sync_stopped
import space.opensensor.strings.resources.msg_thing_changed
import space.opensensor.strings.resources.msg_token_needs_ble
import space.opensensor.strings.resources.msg_token_rotated_fetch
import space.opensensor.strings.resources.msg_token_rotated_lan
import space.opensensor.strings.resources.msg_token_rotated_stored
import space.opensensor.strings.resources.msg_unplaced
import space.opensensor.strings.resources.msg_up_to_date
import space.opensensor.strings.resources.msg_verification_failed
import space.opensensor.strings.resources.msg_wifi_off
import space.opensensor.strings.resources.msg_wifi_on
import space.opensensor.strings.resources.msg_wifi_sent
import space.opensensor.strings.resources.unit_bytes
import space.opensensor.strings.resources.unit_gigabytes
import space.opensensor.strings.resources.unit_kilobytes
import space.opensensor.strings.resources.unit_megabytes
import space.opensensor.units.ByteUnit
import space.opensensor.units.byteSize

/**
 * Every [DeviceMessage] to its words in this catalogue. Both phones read this one mapping, Android
 * for its snackbars and iOS for its link notices, so a device message reads the same on each.
 */
fun DeviceMessage.text(): UiText = when (this) {
    DeviceMessage.BackgroundOwnsConnect -> UiText.of(Res.string.msg_background_owns_connect)
    DeviceMessage.BackgroundOwnsSync -> UiText.of(Res.string.msg_background_owns_sync)
    DeviceMessage.BackgroundOwnsCommand -> UiText.of(Res.string.msg_background_owns_command)
    DeviceMessage.BusyBeforeSync -> UiText.of(Res.string.msg_busy_before_sync)
    DeviceMessage.Busy -> UiText.of(Res.string.msg_busy)
    DeviceMessage.ConnectFailed -> UiText.of(Res.string.conn_failed)
    is DeviceMessage.BluetoothUnavailable -> UiText.of(
        when (kind) {
            UnavailableKind.RADIO_OFF -> Res.string.msg_bluetooth_off
            UnavailableKind.PERMISSION_MISSING -> Res.string.msg_bluetooth_permission
            UnavailableKind.NO_ADAPTER -> Res.string.msg_bluetooth_missing
        },
    )
    DeviceMessage.PairingRepair -> UiText.of(Res.string.conn_pairing_repair)
    is DeviceMessage.NewerProtocol -> UiText.of(Res.string.msg_newer_protocol, device, app)
    is DeviceMessage.FirmwareUpdateRequired ->
        device?.let { UiText.of(Res.string.msg_firmware_update_required, it, app) }
            ?: UiText.of(Res.string.msg_firmware_update_required_unknown, app)
    is DeviceMessage.IdentityRejected -> UiText.of(reason.resource())
    DeviceMessage.HardwareInUse -> UiText.of(Res.string.msg_hardware_in_use)
    DeviceMessage.VerificationFailed -> UiText.of(Res.string.msg_verification_failed)
    DeviceMessage.ThingChanged -> UiText.of(Res.string.msg_thing_changed)
    is DeviceMessage.Reconnecting -> UiText.of(Res.string.msg_reconnecting, attempt, max)
    is DeviceMessage.ReconnectFailed -> UiText.plural(Res.plurals.msg_reconnect_failed, attempts, attempts)
    DeviceMessage.LanTokenStored -> UiText.of(Res.string.msg_lan_token_stored)
    DeviceMessage.TokenNeedsBluetooth -> UiText.of(Res.string.msg_token_needs_ble)
    is DeviceMessage.TokenRotated -> UiText.of(
        when (outcome) {
            DeviceMessage.TokenRotation.LAN_SESSION_DROPS -> Res.string.msg_token_rotated_lan
            DeviceMessage.TokenRotation.STORED -> Res.string.msg_token_rotated_stored
            DeviceMessage.TokenRotation.FETCH_LATER -> Res.string.msg_token_rotated_fetch
        },
    )
    DeviceMessage.FileListNotReady -> UiText.of(Res.string.msg_file_list_not_ready)
    DeviceMessage.NoThingId -> UiText.of(Res.string.msg_no_thing)
    DeviceMessage.UpToDate -> UiText.of(Res.string.msg_up_to_date)
    is DeviceMessage.FileNotSynced -> UiText.of(
        when (problem) {
            DeviceMessage.FileProblem.CHECK_FAILED -> Res.string.msg_file_check_failed
            DeviceMessage.FileProblem.DEVICE_REFUSED -> Res.string.msg_file_refused
            DeviceMessage.FileProblem.FAILED -> Res.string.msg_file_failed
        },
        name,
    )
    is DeviceMessage.SyncFinished -> UiText.plural(Res.plurals.msg_sync_finished, total, synced, total)
    DeviceMessage.SyncStopped -> UiText.of(Res.string.msg_sync_stopped)
    is DeviceMessage.SyncEnded -> reason.text()
    is DeviceMessage.NotEnoughSpace -> UiText.of(Res.string.msg_not_enough_space, bytesText(shortBytes))
    is DeviceMessage.UnplacedReadings -> UiText.plural(Res.plurals.msg_unplaced, rows.toCount(), rows)
    is DeviceMessage.Finalized -> UiText.plural(Res.plurals.msg_finalized, rows, rows, files)
    is DeviceMessage.ConfigSaved ->
        if (rebootRequired) UiText.of(Res.string.msg_saved_after_reboot, UiText.of(change.resource())) else UiText.of(change.resource())
    is DeviceMessage.ConfigKeyRejected -> UiText.of(configRejected(key))
    DeviceMessage.SecretNeedsBluetooth -> UiText.of(Res.string.msg_secret_needs_bluetooth)
    DeviceMessage.NoRebootWhileSyncing -> UiText.of(Res.string.msg_no_reboot_syncing)
    DeviceMessage.Rebooting -> UiText.of(Res.string.msg_rebooting)
    is DeviceMessage.CommandRejected -> UiText.of(command.rejected())
    is DeviceMessage.CommandFailed -> UiText.of(command.failed())
}

/** One line per way a sync can end, for the snackbar, the notification and the iOS sync line. */
fun SyncEndReason.text(): UiText = UiText.of(
    when (this) {
        SyncEndReason.COMPLETED -> Res.string.msg_up_to_date
        SyncEndReason.STOPPED -> Res.string.msg_sync_stopped
        SyncEndReason.INTERRUPTED -> Res.string.msg_sync_end_interrupted
        SyncEndReason.BLUETOOTH_OFF -> Res.string.msg_sync_end_bluetooth_off
        SyncEndReason.PERMISSION_MISSING -> Res.string.msg_sync_end_permission
        SyncEndReason.PAIRING_REPAIR -> Res.string.conn_pairing_repair
        // The token renews quietly on the next Bluetooth connection, so both say only that.
        SyncEndReason.TOKEN_ROTATED, SyncEndReason.TOKEN_REJECTED -> Res.string.msg_sync_end_wifi_access
        SyncEndReason.DEVICE_BUSY -> Res.string.msg_sync_end_busy
        SyncEndReason.OUT_OF_RANGE -> Res.string.msg_sync_end_out_of_range
        SyncEndReason.UPDATE_FIRMWARE -> Res.string.msg_sync_end_update_firmware
        SyncEndReason.NO_SPACE -> Res.string.msg_sync_end_no_space
        SyncEndReason.STORAGE_ERROR -> Res.string.msg_sync_end_storage
        SyncEndReason.LINK_LOST -> Res.string.msg_sync_end_link_lost
        SyncEndReason.OTHER -> Res.string.msg_sync_aborted
    },
)

/** A refused key names its settings card, never the wire key. */
private fun configRejected(key: String): StringResource = when (ConfigFields.groupOf(key)) {
    ConfigGroup.PAIRING -> Res.string.msg_config_rejected_pairing
    ConfigGroup.WIFI -> Res.string.msg_config_rejected_wifi
    ConfigGroup.LAN -> Res.string.msg_config_rejected_lan
    ConfigGroup.LOCATION -> Res.string.msg_config_rejected_location
    ConfigGroup.SITE -> Res.string.msg_config_rejected_site
    ConfigGroup.SENSOR -> Res.string.msg_config_rejected_sensor
    ConfigGroup.OTHER -> Res.string.msg_config_rejected_other
}

private fun ConfigChange.resource(): StringResource = when (this) {
    ConfigChange.SETTINGS -> Res.string.msg_settings_saved
    ConfigChange.PAIRING -> Res.string.msg_pairing_saved
    ConfigChange.PIN -> Res.string.msg_pin_saved
    ConfigChange.WIFI_SENT -> Res.string.msg_wifi_sent
    ConfigChange.WIFI_ON -> Res.string.msg_wifi_on
    ConfigChange.WIFI_OFF -> Res.string.msg_wifi_off
    ConfigChange.LAN_ON -> Res.string.msg_lan_on
    ConfigChange.LAN_OFF -> Res.string.msg_lan_off
    ConfigChange.BONDS_CLEARED -> Res.string.msg_bonds_cleared
}

private fun DeviceContinuityPolicy.Reason.resource(): StringResource = when (this) {
    DeviceContinuityPolicy.Reason.ENDPOINT_CHANGED -> Res.string.msg_identity_endpoint_changed
    DeviceContinuityPolicy.Reason.HARDWARE_CHANGED -> Res.string.msg_identity_hardware_changed
    DeviceContinuityPolicy.Reason.HARDWARE_MISSING -> Res.string.msg_identity_hardware_missing
    DeviceContinuityPolicy.Reason.HARDWARE_MALFORMED -> Res.string.msg_identity_hardware_malformed
    DeviceContinuityPolicy.Reason.THING_MISSING -> Res.string.msg_identity_thing_missing
    DeviceContinuityPolicy.Reason.THING_MALFORMED -> Res.string.msg_identity_thing_malformed
    DeviceContinuityPolicy.Reason.ENROLLMENT_REQUIRES_BLE -> Res.string.msg_identity_enroll_ble
    DeviceContinuityPolicy.Reason.THING_CHANGE_REQUIRES_BLE -> Res.string.msg_identity_thing_ble
    DeviceContinuityPolicy.Reason.THING_CHANGE_REQUIRES_HARDWARE -> Res.string.msg_identity_thing_hardware
}

private fun Command.rejected(): StringResource = when (this) {
    Command.GET_TOKEN -> Res.string.msg_rejected_get_token
    Command.FLUSH -> Res.string.msg_rejected_flush
    Command.GET_CONFIG -> Res.string.msg_rejected_get_config
    Command.SET_CONFIG -> Res.string.msg_rejected_set_config
    Command.REBOOT -> Res.string.msg_rejected_reboot
    Command.ROTATE_TOKEN -> Res.string.msg_rejected_rotate_token
}

private fun Command.failed(): StringResource = when (this) {
    Command.GET_TOKEN -> Res.string.msg_failed_get_token
    Command.FLUSH -> Res.string.msg_failed_flush
    Command.GET_CONFIG -> Res.string.msg_failed_get_config
    Command.SET_CONFIG -> Res.string.msg_failed_set_config
    Command.REBOOT -> Res.string.msg_failed_reboot
    Command.ROTATE_TOKEN -> Res.string.msg_failed_rotate_token
}

/** A byte count, `1.5 KB` or `1.5 كيلوبايت`, through the shared [byteSize] that Files uses too. */
fun bytesText(bytes: Long): UiText {
    val size = byteSize(bytes)
    val res = when (size.unit) {
        ByteUnit.B -> Res.string.unit_bytes
        ByteUnit.KB -> Res.string.unit_kilobytes
        ByteUnit.MB -> Res.string.unit_megabytes
        ByteUnit.GB -> Res.string.unit_gigabytes
    }
    return UiText.of(res, size.number)
}

private fun Long.toCount(): Int = coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
