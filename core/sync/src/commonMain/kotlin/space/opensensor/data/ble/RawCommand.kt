package space.opensensor.data.ble

import kotlinx.coroutines.CancellationException
import space.opensensor.data.protocol.Frame
import space.opensensor.data.protocol.Opcode

/** What [RawCommandPolicy.check] made of the hex the user typed. */
sealed interface RawCommandCheck {
    class Ready(val bytes: ByteArray) : RawCommandCheck
    data object InvalidHex : RawCommandCheck
    data object BadLength : RawCommandCheck

    /** [opcode] is on the denylist. It needs the Device screen and its confirmation. */
    data class Refused(val opcode: Int) : RawCommandCheck {
        val name: String get() = Opcode.name(opcode)
    }
}

/**
 * The single denylist for the Debug raw command on Android and iOS. `REBOOT` restarts the logger
 * and `SET_CONFIG` can rotate the token, forget bonds or disable the passkey. Both need the Device
 * screen's confirmation (root `AGENTS.md`), so the raw box never sends them.
 * `SET_TIME` is automatic on connection and is never exposed as a manual command.
 */
object RawCommandPolicy {
    val DENYLIST: Set<Int> = setOf(Opcode.REBOOT, Opcode.SET_CONFIG, Opcode.SET_TIME)
    const val MAX_BYTES = 512

    fun isRefused(request: ByteArray): Boolean = request.isNotEmpty() && (request[0].toInt() and 0xFF) in DENYLIST

    /** Hex in, whitespace allowed, upper or lower case. Only `0-9a-fA-F` pairs are accepted. */
    fun check(hex: String): RawCommandCheck {
        val clean = hex.filterNot { it.isWhitespace() }
        if (clean.length % 2 != 0 || clean.any { digit(it) < 0 }) return RawCommandCheck.InvalidHex
        val bytes = ByteArray(clean.length / 2) { i -> ((digit(clean[2 * i]) shl 4) or digit(clean[2 * i + 1])).toByte() }
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return RawCommandCheck.BadLength
        if (isRefused(bytes)) return RawCommandCheck.Refused(bytes[0].toInt() and 0xFF)
        return RawCommandCheck.Ready(bytes)
    }

    private fun digit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }
}

/** One reply line of a raw command, already redacted. */
data class RawReply(val hex: String, val decoded: String)

/** The result of [RawCommandSender.send]. */
sealed interface RawCommandOutcome {
    data class Replies(val replies: List<RawReply>) : RawCommandOutcome
    data object InvalidHex : RawCommandOutcome
    data object BadLength : RawCommandOutcome
    data class Refused(val opcode: String) : RawCommandOutcome
    data object NoResponse : RawCommandOutcome

    /** Transport or protocol detail, shown as is at Developer level. */
    data class Failed(val detail: String) : RawCommandOutcome
}

/**
 * Checks the hex against [RawCommandPolicy], sends it through [exchange] (normally
 * [DeviceSession.rawCommand], which enforces the denylist again) and renders every reply through
 * [FrameRedaction]. Platform code supplies only the exchange.
 */
class RawCommandSender(private val exchange: suspend (ByteArray) -> List<Pair<ByteArray, Frame?>>) {
    suspend fun send(hex: String): RawCommandOutcome {
        val bytes = when (val c = RawCommandPolicy.check(hex)) {
            is RawCommandCheck.Ready -> c.bytes
            RawCommandCheck.InvalidHex -> return RawCommandOutcome.InvalidHex
            RawCommandCheck.BadLength -> return RawCommandOutcome.BadLength
            is RawCommandCheck.Refused -> return RawCommandOutcome.Refused(c.name)
        }
        val got = try {
            exchange(bytes)
        } catch (e: RawCommandRefusedException) {
            return RawCommandOutcome.Refused(Opcode.name(e.op))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return RawCommandOutcome.Failed(e.message ?: e::class.simpleName.orEmpty())
        }
        if (got.isEmpty()) return RawCommandOutcome.NoResponse
        return RawCommandOutcome.Replies(
            got.map { (raw, frame) ->
                RawReply(
                    hex = FrameRedaction.replyHex(raw),
                    decoded = frame?.let(FrameRedaction::describe) ?: "? (${raw.size} B)",
                )
            },
        )
    }
}
