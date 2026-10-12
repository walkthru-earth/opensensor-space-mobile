package earth.walkthru.aqsync.link

import earth.walkthru.aqsync.data.protocol.DisconnectReason
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

    /**
     * The phone cannot open this transport at all until the user acts: the radio is off, a
     * permission is missing or there is no adapter. Retrying without that action repeats it.
     */
    data class Unavailable(val kind: UnavailableKind) : DisconnectCause

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

    /** The device rotated its LAN token (BYE reason 10). A fresh token comes over bonded BLE. */
    TOKEN_ROTATED,
}

/** Why a transport cannot be opened before any byte moves. */
enum class UnavailableKind { RADIO_OFF, PERMISSION_MISSING, NO_ADAPTER }

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
        is DisconnectCause.Unavailable,
        is DisconnectCause.UpdateFirmware -> false
        else -> true
    }

/**
 * Why the link ended when the peer closed it after [bye]. A BYE that names a credential or slot
 * problem becomes its typed cause, so retry policy never reconnects with a rotated or refused LAN
 * token and screens name the real reason. Any other BYE, or none, stays [DisconnectCause.PeerClosed].
 */
fun peerClosedCause(bye: Frame.Bye?): DisconnectCause = when (bye?.disconnectReason) {
    DisconnectReason.TOKEN_ROTATED -> DisconnectCause.Auth(AuthKind.TOKEN_ROTATED)
    DisconnectReason.AUTH_FAILED -> DisconnectCause.Auth(AuthKind.TOKEN_REJECTED)
    DisconnectReason.SESSIONS_FULL -> DisconnectCause.Busy
    else -> DisconnectCause.PeerClosed(bye)
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
    is DisconnectCause.Unavailable -> "unavailable (${kind.name.lowercase()})"
    is DisconnectCause.UpdateFirmware -> "firmware proto $reported, app needs $required"
    is DisconnectCause.Rejected -> "rejected ($reason)"
    is DisconnectCause.Deliberate -> "closed ($reason)"
}
