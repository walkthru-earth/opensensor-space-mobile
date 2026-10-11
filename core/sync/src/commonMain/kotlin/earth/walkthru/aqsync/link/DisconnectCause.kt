package earth.walkthru.aqsync.link

import earth.walkthru.aqsync.data.protocol.Frame
import earth.walkthru.aqsync.data.protocol.Opcode
import kotlin.time.Duration

/**
 * Why a physical link ended. Typed so resume policy and UI can tell an OS socket kill from an
 * authentication failure without parsing free text. Values carry counts and codes only, never
 * frame bodies, tokens, PIN or PSK.
 */
sealed interface DisconnectCause {
    /** The peer closed the stream. [bye] is the firmware BYE when one arrived before EOF. */
    data class PeerClosed(val bye: Frame.Bye? = null) : DisconnectCause

    /** The peer reset the connection without an orderly close. */
    data object PeerReset : DisconnectCause

    /** The phone's own stack ended the link. [errno] for sockets, [gattStatus] for BLE. */
    data class LocalAbort(val errno: Int? = null, val gattStatus: Int? = null) : DisconnectCause

    /** Nothing was read from the peer for [rxIdle]. Consumers never feed this deadline. */
    data class HeartbeatDeadline(val rxIdle: Duration) : DisconnectCause

    /** The byte stream broke the framing contract. [detail] names the rule, never the bytes. */
    data class FramingViolation(val detail: String) : DisconnectCause

    /** Undelivered reply bytes passed the session budget, so the reader was stopped. */
    data class ResponseOverflow(val queuedBytes: Long, val budget: Long) : DisconnectCause

    /** An abandoned reply to [op] did not reach its terminal frame within its [DrainBound]. */
    data class DrainExceeded(val op: Int, val frames: Int, val elapsed: Duration) : DisconnectCause

    /** A deadline in [phase] passed for [op] (0 outside a request). */
    data class Timeout(val phase: TimeoutPhase, val op: Int) : DisconnectCause

    /** The peer refused the credentials. */
    data class Auth(val kind: AuthKind) : DisconnectCause

    /** The peer has no free session slot. */
    data object Busy : DisconnectCause

    /** The peer could not be reached at all. */
    data object Unreachable : DisconnectCause

    /** The phone lost the radio that carried the link. */
    data class RadioLost(val radio: Radio) : DisconnectCause

    /** The device speaks an older protocol than this app requires. */
    data class UpdateFirmware(val reported: Int?, val required: Int) : DisconnectCause

    /**
     * The peer answered, but the app refused the session: another identity, hardware held through
     * another endpoint, a failed setup step or a replaced session. [reason] is a fixed developer label.
     */
    data class Rejected(val reason: String) : DisconnectCause

    /** The app closed the link on purpose. [reason] is a fixed developer label. */
    data class Deliberate(val reason: String) : DisconnectCause
}

enum class TimeoutPhase { OPEN, HANDSHAKE, SEND, FIRST_FRAME, INTER_FRAME }

enum class AuthKind {
    TOKEN_REJECTED,
    BOND_FAILED,
    ENCRYPTION_FAILED,

    /** The phone lost its bond keys for the peer (Android `ACTION_KEY_MISSING`). Pair again. */
    BOND_MISSING,
}

enum class Radio { BLUETOOTH, WIFI }

/**
 * Whether reconnecting can help without user action. A rejected credential, an app decision, a
 * framing bug and an old firmware all repeat on the next attempt.
 */
val DisconnectCause.retryable: Boolean
    get() = when (this) {
        is DisconnectCause.Auth,
        is DisconnectCause.Deliberate,
        is DisconnectCause.FramingViolation,
        is DisconnectCause.Rejected,
        is DisconnectCause.UpdateFirmware -> false
        else -> true
    }

/** Short label for logs. Contains codes and counts only. */
fun DisconnectCause.describe(): String = when (this) {
    is DisconnectCause.PeerClosed -> bye?.let { "peer closed (bye ${it.disconnectReason.wireName})" } ?: "peer closed"
    DisconnectCause.PeerReset -> "peer reset"
    is DisconnectCause.LocalAbort -> "local abort errno=$errno gatt=$gattStatus"
    is DisconnectCause.HeartbeatDeadline -> "no data for $rxIdle"
    is DisconnectCause.FramingViolation -> "framing violation ($detail)"
    is DisconnectCause.ResponseOverflow -> "response overflow $queuedBytes of $budget bytes"
    is DisconnectCause.DrainExceeded -> "drain of ${Opcode.name(op)} exceeded after $frames frames in $elapsed"
    is DisconnectCause.Timeout -> "timeout in ${phase.name.lowercase()} of ${Opcode.name(op)}"
    is DisconnectCause.Auth -> "auth ${kind.name.lowercase()}"
    DisconnectCause.Busy -> "device busy"
    DisconnectCause.Unreachable -> "unreachable"
    is DisconnectCause.RadioLost -> "${radio.name.lowercase()} lost"
    is DisconnectCause.UpdateFirmware -> "firmware proto $reported, app needs $required"
    is DisconnectCause.Rejected -> "rejected ($reason)"
    is DisconnectCause.Deliberate -> "closed ($reason)"
}
