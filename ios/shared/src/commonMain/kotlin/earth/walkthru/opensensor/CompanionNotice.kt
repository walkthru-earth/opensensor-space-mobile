package earth.walkthru.opensensor

import earth.walkthru.opensensor.strings.UiText
import earth.walkthru.opensensor.strings.resources.Res
import earth.walkthru.opensensor.strings.resources.companion_info_invalid
import earth.walkthru.opensensor.strings.resources.companion_lan_needs_access
import earth.walkthru.opensensor.strings.resources.msg_busy
import earth.walkthru.opensensor.strings.resources.msg_busy_before_sync
import earth.walkthru.opensensor.strings.resources.msg_failed_get_token
import earth.walkthru.opensensor.strings.resources.msg_hardware_in_use
import earth.walkthru.opensensor.strings.resources.msg_identity_enroll_ble
import earth.walkthru.opensensor.strings.resources.msg_identity_hardware_changed
import earth.walkthru.opensensor.strings.resources.msg_identity_station_ble
import earth.walkthru.opensensor.strings.resources.msg_lan_token_stored
import earth.walkthru.opensensor.strings.resources.msg_rejected_get_token
import earth.walkthru.opensensor.strings.resources.msg_sync_aborted
import earth.walkthru.opensensor.strings.resources.msg_token_needs_ble
import earth.walkthru.opensensor.strings.resources.msg_verification_failed

/**
 * A link or Wi-Fi access event the Swift adapters report by kind. The state picks the words, so
 * iOS shows Android's keys and wording. Notices never carry a token, PIN or passphrase.
 */
enum class CompanionNotice {
    /** No Wi-Fi access is saved for this sensor yet. */
    ENROLL_OVER_BLUETOOTH,

    /** Wi-Fi access can be saved now over the open Bluetooth connection. */
    SAVE_WIFI_ACCESS,

    /** The sensor refused the saved Wi-Fi access. It is dropped and renews over Bluetooth. */
    TOKEN_NEEDS_BLUETOOTH,

    /** HELLO named another station than the saved access. */
    STATION_CHANGED,

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

    /** Wi-Fi access arrived over Bluetooth and is stored in the Keychain. */
    TOKEN_STORED,

    /** The Wi-Fi access request or its secure storage failed. */
    TOKEN_FAILED,

    /** The sensor refused to share its Wi-Fi access. */
    TOKEN_REFUSED,

    /** INFO was unsupported or invalid. */
    INFO_INVALID,
    ;

    internal val text: UiText
        get() = UiText.of(
            when (this) {
                ENROLL_OVER_BLUETOOTH -> Res.string.msg_identity_enroll_ble
                SAVE_WIFI_ACCESS -> Res.string.companion_lan_needs_access
                TOKEN_NEEDS_BLUETOOTH -> Res.string.msg_token_needs_ble
                STATION_CHANGED -> Res.string.msg_identity_station_ble
                HARDWARE_CHANGED -> Res.string.msg_identity_hardware_changed
                VERIFICATION_FAILED -> Res.string.msg_verification_failed
                BUSY -> Res.string.msg_busy
                BUSY_BEFORE_SYNC -> Res.string.msg_busy_before_sync
                HARDWARE_IN_USE -> Res.string.msg_hardware_in_use
                SYNC_ABORTED -> Res.string.msg_sync_aborted
                TOKEN_STORED -> Res.string.msg_lan_token_stored
                TOKEN_FAILED -> Res.string.msg_failed_get_token
                TOKEN_REFUSED -> Res.string.msg_rejected_get_token
                INFO_INVALID -> Res.string.companion_info_invalid
            },
        )
}
