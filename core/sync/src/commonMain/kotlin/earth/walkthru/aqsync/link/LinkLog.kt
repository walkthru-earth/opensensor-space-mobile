package earth.walkthru.aqsync.link

import earth.walkthru.aqsync.data.ble.TransportKind
import earth.walkthru.aqsync.data.protocol.FrameType
import earth.walkthru.aqsync.data.protocol.Opcode

/**
 * What the session reports about itself. Events carry opcodes, frame types and counts only,
 * never frame bodies, tokens, PIN or PSK, so any sink may write them to a log.
 */
sealed interface LinkEvent {
    data class Opened(val kind: TransportKind, val proto: Int?, val payloadMax: Int) : LinkEvent
    data class Sent(val op: Int) : LinkEvent
    data class Completed(val op: Int, val frames: Int) : LinkEvent
    data class Abandoned(val op: Int, val framesSeen: Int, val written: Boolean) : LinkEvent
    data class AbortSent(val op: Int) : LinkEvent
    data class Drained(val op: Int, val frames: Int, val bytes: Long) : LinkEvent
    data class ForeignDropped(val type: Int, val op: Int) : LinkEvent
    data class UnknownDropped(val type: Int) : LinkEvent
    data class ByeReceived(val reason: Int) : LinkEvent
    data class Closed(val cause: DisconnectCause) : LinkEvent
}

fun interface LinkLog {
    fun event(event: LinkEvent)

    companion object {
        val NONE: LinkLog = LinkLog { }
    }
}

/** One line per event, for platform loggers. */
fun LinkEvent.describe(): String = when (this) {
    is LinkEvent.Opened -> "opened ${kind.name} proto=$proto payload_max=$payloadMax"
    is LinkEvent.Sent -> "sent ${Opcode.name(op)}"
    is LinkEvent.Completed -> "${Opcode.name(op)} done after $frames frames"
    is LinkEvent.Abandoned -> "${Opcode.name(op)} abandoned after $framesSeen frames written=$written"
    is LinkEvent.AbortSent -> "ABORT sent for ${Opcode.name(op)}"
    is LinkEvent.Drained -> "${Opcode.name(op)} drained $frames frames $bytes bytes"
    is LinkEvent.ForeignDropped -> "dropped ${FrameType.name(type)} while ${Opcode.name(op)} owns the link"
    is LinkEvent.UnknownDropped -> "dropped unknown frame ${FrameType.name(type)}"
    is LinkEvent.ByeReceived -> "BYE reason=$reason"
    is LinkEvent.Closed -> "closed, ${cause.describe()}"
}
