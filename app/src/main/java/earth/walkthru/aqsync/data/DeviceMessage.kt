package earth.walkthru.aqsync.data

import earth.walkthru.aqsync.collection.SyncEndReason
import earth.walkthru.aqsync.data.lan.LanFailure
import earth.walkthru.aqsync.link.UnavailableKind

/**
 * What a device snackbar says, as a code with typed arguments. The data layer never builds
 * user-facing sentences. `ui/components/DeviceMessages.kt` maps every case to a string resource,
 * and raw technical detail (exception messages, GATT codes) goes to the log only.
 */
sealed interface DeviceMessage {
    /** Device requests a message can name. */
    enum class Command { GET_TOKEN, SET_TIME, FLUSH, GET_CONFIG, SET_CONFIG, REBOOT, ROTATE_TOKEN }

    /** Which settings a successful `SET_CONFIG` changed, for the confirmation line. */
    enum class ConfigChange { SETTINGS, PAIRING, PIN, WIFI_SENT, WIFI_ON, WIFI_OFF, LAN_ON, LAN_OFF, BONDS_CLEARED }

    /** What happened to the stored token after a rotation. */
    enum class TokenRotation { LAN_SESSION_DROPS, STORED, FETCH_LATER }

    /** Why one file did not sync. */
    enum class FileProblem { CHECK_FAILED, DEVICE_REFUSED, FAILED }

    // Ownership and the operation gate
    data object BackgroundOwnsConnect : DeviceMessage
    data object BackgroundOwnsSync : DeviceMessage
    data object BackgroundOwnsCommand : DeviceMessage
    data object BusyBeforeSync : DeviceMessage
    data object Busy : DeviceMessage

    // Connecting

    /** The LAN attempt failed and Bluetooth is used. [failure] is null for an untyped error. */
    data class LanFallback(val failure: LanFailure?) : DeviceMessage
    data object ConnectFailed : DeviceMessage

    /** Bluetooth is off, missing, or its permission is not granted, so a connect never started. */
    data class BluetoothUnavailable(val kind: UnavailableKind) : DeviceMessage

    /** The sensor refused this phone's bond keys, for example after a reset. Pair again in Settings. */
    data object PairingRepair : DeviceMessage
    data class NewerProtocol(val device: Int, val app: Int) : DeviceMessage

    /** The sensor reports protocol [device] (null when absent), below the [app] minimum. */
    data class FirmwareUpdateRequired(val device: Int?, val app: Int) : DeviceMessage
    data class IdentityRejected(val reason: DeviceContinuityPolicy.Reason) : DeviceMessage
    data object HardwareInUse : DeviceMessage
    data object VerificationFailed : DeviceMessage
    data object StationChanged : DeviceMessage
    data class Reconnecting(val attempt: Int, val max: Int) : DeviceMessage
    data class ReconnectFailed(val attempts: Int) : DeviceMessage

    // LAN token
    data object LanTokenRenewed : DeviceMessage
    data object LanTokenStored : DeviceMessage
    data object TokenNeedsBluetooth : DeviceMessage
    data class TokenRotated(val outcome: TokenRotation) : DeviceMessage

    // Sync
    data object FileListNotReady : DeviceMessage
    data object NoStationId : DeviceMessage
    data object UpToDate : DeviceMessage
    data class FileNotSynced(val name: String, val problem: FileProblem) : DeviceMessage
    data class SyncFinished(val synced: Int, val total: Int) : DeviceMessage
    data object SyncStopped : DeviceMessage

    /**
     * A collection ended before finishing. [reason] is the shared [SyncEndReason], so the
     * snackbar, the notification and the iOS screen word the same ending the same way.
     */
    data class SyncEnded(val reason: SyncEndReason) : DeviceMessage

    /** The planned files do not fit, [shortBytes] more must be freed. Nothing was opened. */
    data class NotEnoughSpace(val shortBytes: Long) : DeviceMessage
    data class UnplacedReadings(val rows: Long) : DeviceMessage

    // Device commands
    data object ClockSet : DeviceMessage
    data class Finalized(val rows: Int, val files: Long) : DeviceMessage
    data class ConfigSaved(val change: ConfigChange, val rebootRequired: Boolean = false) : DeviceMessage

    /** `ERROR` 12. [key] is the protocol key the device named, shown verbatim. */
    data class ConfigKeyRejected(val key: String) : DeviceMessage

    /** A passphrase or PIN patch was refused before sending, the live link is plaintext LAN (#155). */
    data object SecretNeedsBluetooth : DeviceMessage
    data object NoRebootWhileSyncing : DeviceMessage
    data object Rebooting : DeviceMessage
    data class CommandRejected(val command: Command) : DeviceMessage
    data class CommandFailed(val command: Command) : DeviceMessage
}

/** One-shot messages for snackbars and dialogs. */
sealed interface DeviceEvent {
    data class Info(val message: DeviceMessage) : DeviceEvent
    data class Error(val message: DeviceMessage) : DeviceEvent
}
