package earth.walkthru.aqsync.collection

import earth.walkthru.aqsync.link.AuthKind
import earth.walkthru.aqsync.link.DisconnectCause
import earth.walkthru.aqsync.link.TimeoutPhase
import earth.walkthru.aqsync.link.UnavailableKind

/**
 * Why the last collection ended, in the terms a screen or notification explains to a person. Both
 * platforms map their typed failures here, so every screen words the same ending the same way.
 */
enum class SyncEndReason {
    /** Every planned file got an outcome. Per-file failures stay in the outcomes. */
    COMPLETED,

    /** The user pressed Stop, or another action took the device on purpose. */
    STOPPED,

    /** The OS ended the time the app had off screen. Resumes when the app is back. */
    INTERRUPTED,
    BLUETOOTH_OFF,
    PERMISSION_MISSING,
    PAIRING_REPAIR,

    /** The device rotated its Wi-Fi token. A Bluetooth connection fetches the new one. */
    TOKEN_ROTATED,
    TOKEN_REJECTED,
    DEVICE_BUSY,
    OUT_OF_RANGE,
    UPDATE_FIRMWARE,
    NO_SPACE,
    STORAGE_ERROR,
    LINK_LOST,
    OTHER,
}

fun DisconnectCause.endReason(): SyncEndReason = when (this) {
    is DisconnectCause.Unavailable -> when (kind) {
        UnavailableKind.RADIO_OFF, UnavailableKind.NO_ADAPTER -> SyncEndReason.BLUETOOTH_OFF
        UnavailableKind.PERMISSION_MISSING -> SyncEndReason.PERMISSION_MISSING
    }
    is DisconnectCause.Auth -> when (kind) {
        AuthKind.TOKEN_ROTATED -> SyncEndReason.TOKEN_ROTATED
        AuthKind.TOKEN_REJECTED -> SyncEndReason.TOKEN_REJECTED
        AuthKind.BOND_FAILED, AuthKind.ENCRYPTION_FAILED, AuthKind.BOND_MISSING -> SyncEndReason.PAIRING_REPAIR
    }
    DisconnectCause.Busy -> SyncEndReason.DEVICE_BUSY
    DisconnectCause.Unreachable -> SyncEndReason.OUT_OF_RANGE
    is DisconnectCause.Timeout -> if (phase == TimeoutPhase.OPEN) SyncEndReason.OUT_OF_RANGE else SyncEndReason.LINK_LOST
    is DisconnectCause.UpdateFirmware -> SyncEndReason.UPDATE_FIRMWARE
    is DisconnectCause.Deliberate -> SyncEndReason.STOPPED
    is DisconnectCause.RadioLost -> SyncEndReason.LINK_LOST
    is DisconnectCause.Rejected, is DisconnectCause.FramingViolation -> SyncEndReason.OTHER
    else -> SyncEndReason.LINK_LOST
}

fun CollectionFailure.endReason(): SyncEndReason = when (this) {
    is CollectionFailure.Link -> cause.endReason()
    is CollectionFailure.Storage -> if (kind == StorageFailure.NO_SPACE) SyncEndReason.NO_SPACE else SyncEndReason.STORAGE_ERROR
    is CollectionFailure.Device -> SyncEndReason.OTHER
    is CollectionFailure.KeepAliveRefused -> SyncEndReason.INTERRUPTED
    is CollectionFailure.Unexpected -> SyncEndReason.OTHER
}
