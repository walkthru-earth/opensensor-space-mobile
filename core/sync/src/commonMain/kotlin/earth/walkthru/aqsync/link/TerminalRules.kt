package earth.walkthru.aqsync.link

import earth.walkthru.aqsync.data.protocol.Frame
import earth.walkthru.aqsync.data.protocol.Opcode
import earth.walkthru.aqsync.data.protocol.ProtocolError

/** Where one reply frame belongs relative to the request that is waiting. */
enum class Routing {
    /** Part of the reply, more frames follow. */
    CONTINUE,

    /** The last frame of the reply. */
    TERMINAL,

    /** Not part of this reply, for example a late frame of an earlier request. */
    FOREIGN,
}

/** The only table that maps an opcode to the frames of its reply. */
object TerminalRules {
    /** Largest CONFIG body the firmware slices into CONFIG_CHUNK frames. */
    private const val CONFIG_MAX_BYTES = 480

    /**
     * `ERROR` 16 only answers an `ABORT`, which only a drain sends, so it never ends a live
     * request. A late one after `ABORTED` would otherwise fail the next request of the same op.
     */
    fun classify(op: Int, frame: Frame): Routing {
        if (frame is Frame.Error) {
            if (isAbortError(frame)) return Routing.FOREIGN
            return if (frame.op == op || frame.op == 0) Routing.TERMINAL else Routing.FOREIGN
        }
        return when (op) {
            Opcode.LIST -> when (frame) {
                is Frame.File -> Routing.CONTINUE
                is Frame.ListEnd -> Routing.TERMINAL
                else -> Routing.FOREIGN
            }
            Opcode.OPEN -> terminalIf(frame is Frame.Opened)
            Opcode.READ -> when (frame) {
                is Frame.Chunk -> Routing.CONTINUE
                is Frame.ReadEnd -> Routing.TERMINAL
                else -> Routing.FOREIGN
            }
            Opcode.CLOSE -> terminalIf(frame is Frame.Closed)
            Opcode.SET_TIME -> terminalIf(frame is Frame.TimeSet)
            Opcode.FLUSH -> terminalIf(frame is Frame.Flushed)
            Opcode.GET_CONFIG, Opcode.SET_CONFIG -> when (frame) {
                is Frame.Config -> Routing.TERMINAL
                is Frame.ConfigChunk -> if (chunkEnds(frame)) Routing.TERMINAL else Routing.CONTINUE
                else -> Routing.FOREIGN
            }
            Opcode.WIFI_SCAN -> when (frame) {
                is Frame.WifiAp -> Routing.CONTINUE
                is Frame.WifiScanEnd -> Routing.TERMINAL
                else -> Routing.FOREIGN
            }
            Opcode.REBOOT -> terminalIf(frame is Frame.Rebooting)
            Opcode.LOG_TAIL -> when (frame) {
                is Frame.Log -> Routing.CONTINUE
                is Frame.LogEnd -> Routing.TERMINAL
                else -> Routing.FOREIGN
            }
            Opcode.GET_TOKEN -> terminalIf(frame is Frame.Token)
            Opcode.GET_DIAG -> terminalIf(frame is Frame.Diag)
            Opcode.ABORT -> terminalIf(frame is Frame.Aborted)
            Opcode.GET_DESCRIPTOR -> terminalIf(frame is Frame.DescriptorChunk)
            else -> Routing.FOREIGN
        }
    }

    /** The `ERROR` 16 that ends a reply stopped by `ABORT`. */
    fun isAbortError(frame: Frame): Boolean = frame is Frame.Error && frame.code == ProtocolError.ABORTED.code

    private fun terminalIf(matches: Boolean) = if (matches) Routing.TERMINAL else Routing.FOREIGN

    /**
     * The final slice ends the reply. A malformed slice also ends it, so the owner fails on it at
     * once instead of waiting for a completion that can never come.
     */
    private fun chunkEnds(chunk: Frame.ConfigChunk): Boolean {
        val end = chunk.offset + chunk.bytes.size
        val malformed = chunk.bytes.isEmpty() || chunk.total !in 2..CONFIG_MAX_BYTES || end > chunk.total
        return malformed || end == chunk.total
    }
}
