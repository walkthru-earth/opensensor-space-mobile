package space.opensensor

import space.opensensor.collection.SyncEndReason
import space.opensensor.data.DeviceContinuityPolicy
import space.opensensor.data.DeviceMessage
import space.opensensor.data.protocol.DeviceGatt
import space.opensensor.strings.UiText
import space.opensensor.strings.resources.Res
import space.opensensor.strings.resources.companion_ble_characteristics_missing
import space.opensensor.strings.resources.companion_ble_connect_failed
import space.opensensor.strings.resources.companion_ble_denied
import space.opensensor.strings.resources.companion_ble_disconnected
import space.opensensor.strings.resources.companion_ble_notify_failed
import space.opensensor.strings.resources.companion_ble_off
import space.opensensor.strings.resources.companion_ble_pairing_removed
import space.opensensor.strings.resources.companion_ble_read_failed
import space.opensensor.strings.resources.companion_ble_resume_live
import space.opensensor.strings.resources.companion_ble_sensor_gone
import space.opensensor.strings.resources.companion_ble_service_missing
import space.opensensor.strings.resources.companion_ble_starting
import space.opensensor.strings.resources.companion_ble_timed_out
import space.opensensor.strings.resources.companion_ble_unsupported
import space.opensensor.strings.resources.companion_info_invalid
import space.opensensor.strings.resources.companion_lan_connected
import space.opensensor.strings.resources.companion_lan_connecting
import space.opensensor.strings.resources.companion_lan_disconnected
import space.opensensor.strings.resources.companion_lan_gone
import space.opensensor.strings.resources.companion_lan_listing
import space.opensensor.strings.resources.companion_lan_sync_paused
import space.opensensor.strings.resources.companion_lan_sync_queued
import space.opensensor.strings.resources.companion_lan_syncing
import space.opensensor.strings.text

/**
 * A link event the Swift adapters report by kind. The state picks the words, so
 * iOS shows Android's keys and wording. Notices never carry a token, PIN or passphrase.
 */
enum class CompanionNotice {
    /** HELLO named another thing than the saved access. */
    THING_CHANGED,

    /** HELLO named other hardware than the saved access. */
    HARDWARE_CHANGED,

    /** The handshake failed or the sensor answered with something unusable. */
    VERIFICATION_FAILED,

    /** The sensor is busy with other phones. */
    BUSY,

    /** Too many syncs run at once on this phone. */
    BUSY_BEFORE_SYNC,

    /** This sensor already has a connection on this phone. */
    HARDWARE_IN_USE,

    /** The link went away during a sync. Saved files and partial bytes stay. */
    SYNC_ABORTED,

    /** INFO was unsupported or invalid. */
    INFO_INVALID,

    /** INFO reported a `proto` below 3, so the sensor needs a firmware update. */
    FIRMWARE_UPDATE,

    /** The Bluetooth connection did not open in time. */
    CONNECT_TIMED_OUT,

    /** The user closed the Bluetooth connection. */
    DISCONNECTED,

    /** The app left the screen and the idle Bluetooth connection closed. */
    RESUME_LIVE,

    /** The Bluetooth radio is off. */
    BLUETOOTH_OFF,

    /** Bluetooth access is not allowed for this app. */
    BLUETOOTH_DENIED,

    /** This device has no Bluetooth LE. */
    BLUETOOTH_UNSUPPORTED,

    /** Bluetooth is resetting or not known yet. */
    BLUETOOTH_STARTING,

    /** The Bluetooth connection could not open. */
    CONNECT_FAILED,

    /** The sensor dropped the Bluetooth connection. */
    SENSOR_DISCONNECTED,

    /** The sensor has no OpenSensor Sync service. */
    SERVICE_MISSING,

    /** The sensor lacks the live reading characteristics. */
    CHARACTERISTICS_MISSING,

    /** Notifications could not be turned on, usually a pairing problem. */
    NOTIFY_FAILED,

    /** A characteristic read failed, usually a pairing problem. */
    READ_FAILED,

    /** The sensor refused this phone's pairing keys. Forget it in iOS Settings and pair again. */
    PAIRING_REMOVED,

    /** The Wi-Fi sensor is no longer in the browse results. */
    LAN_SENSOR_GONE,

    /** A Wi-Fi session is opening. */
    LAN_CONNECTING,

    /** The Wi-Fi session is open and ready to sync. */
    LAN_CONNECTED,

    /** A resumed Wi-Fi sync waits for another sensor to finish. */
    LAN_SYNC_QUEUED,

    /** A Wi-Fi sync is listing files. */
    LAN_LISTING,

    /** A Wi-Fi sync is transferring verified files. */
    LAN_SYNCING,

    /** A Wi-Fi sync paused in the background and resumes on return. */
    LAN_PAUSED,

    /** The Wi-Fi session closed. */
    LAN_DISCONNECTED,
    ;

    /** A notice that is also a shared [DeviceMessage] reads through its mapping, so both phones word it once. */
    internal val text: UiText
        get() = when (this) {
            THING_CHANGED -> DeviceMessage.IdentityRejected(DeviceContinuityPolicy.Reason.THING_CHANGE_REQUIRES_BLE).text()
            HARDWARE_CHANGED -> DeviceMessage.IdentityRejected(DeviceContinuityPolicy.Reason.HARDWARE_CHANGED).text()
            VERIFICATION_FAILED -> DeviceMessage.VerificationFailed.text()
            BUSY -> DeviceMessage.Busy.text()
            BUSY_BEFORE_SYNC -> DeviceMessage.BusyBeforeSync.text()
            HARDWARE_IN_USE -> DeviceMessage.HardwareInUse.text()
            SYNC_ABORTED -> DeviceMessage.SyncEnded(SyncEndReason.OTHER).text()
            INFO_INVALID -> UiText.of(Res.string.companion_info_invalid)
            FIRMWARE_UPDATE -> DeviceMessage.FirmwareUpdateRequired(device = null, app = DeviceGatt.PROTOCOL_VERSION).text()
            CONNECT_TIMED_OUT -> UiText.of(Res.string.companion_ble_timed_out)
            DISCONNECTED -> UiText.of(Res.string.companion_ble_disconnected)
            RESUME_LIVE -> UiText.of(Res.string.companion_ble_resume_live)
            BLUETOOTH_OFF -> UiText.of(Res.string.companion_ble_off)
            BLUETOOTH_DENIED -> UiText.of(Res.string.companion_ble_denied)
            BLUETOOTH_UNSUPPORTED -> UiText.of(Res.string.companion_ble_unsupported)
            BLUETOOTH_STARTING -> UiText.of(Res.string.companion_ble_starting)
            CONNECT_FAILED -> UiText.of(Res.string.companion_ble_connect_failed)
            SENSOR_DISCONNECTED -> UiText.of(Res.string.companion_ble_sensor_gone)
            SERVICE_MISSING -> UiText.of(Res.string.companion_ble_service_missing)
            CHARACTERISTICS_MISSING -> UiText.of(Res.string.companion_ble_characteristics_missing)
            NOTIFY_FAILED -> UiText.of(Res.string.companion_ble_notify_failed)
            READ_FAILED -> UiText.of(Res.string.companion_ble_read_failed)
            PAIRING_REMOVED -> UiText.of(Res.string.companion_ble_pairing_removed)
            LAN_SENSOR_GONE -> UiText.of(Res.string.companion_lan_gone)
            LAN_CONNECTING -> UiText.of(Res.string.companion_lan_connecting)
            LAN_CONNECTED -> UiText.of(Res.string.companion_lan_connected)
            LAN_SYNC_QUEUED -> UiText.of(Res.string.companion_lan_sync_queued)
            LAN_LISTING -> UiText.of(Res.string.companion_lan_listing)
            LAN_SYNCING -> UiText.of(Res.string.companion_lan_syncing)
            LAN_PAUSED -> UiText.of(Res.string.companion_lan_sync_paused)
            LAN_DISCONNECTED -> UiText.of(Res.string.companion_lan_disconnected)
        }
}
