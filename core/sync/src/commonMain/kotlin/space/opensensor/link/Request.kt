package space.opensensor.link

import space.opensensor.data.ble.TransportKind
import space.opensensor.data.protocol.Opcode
import space.opensensor.data.protocol.Request
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How much an abandoned reply may still cost before the link is given up. Past any limit the
 * session closes with [DisconnectCause.DrainExceeded].
 */
data class DrainBound(
    val maxFrames: Int = 4096,
    val maxBytes: Long = 4L * 1024 * 1024,
    val maxTime: Duration = 30.seconds,
)

/**
 * One request on the link. Timeouts measure progress, so a long LIST or READ never times out
 * while frames keep arriving. [firstFrameTimeout] runs from the write to the first reply frame,
 * [interFrameTimeout] between reply frames.
 */
class RequestSpec(
    val op: Int,
    val body: ByteArray,
    val firstFrameTimeout: Duration = DEFAULT_FIRST_FRAME,
    val interFrameTimeout: Duration = INTER_FRAME,
    val expectsResponse: Boolean = true,
    val drainBound: DrainBound = DrainBound(),
    val writeDeadline: Duration = WRITE_DEADLINE,
) {
    init {
        require(body.isNotEmpty() && (body[0].toInt() and 0xFF) == op) { "body must start with opcode ${Opcode.name(op)}" }
    }

    override fun toString(): String = "RequestSpec(${Opcode.name(op)}, ${body.size} bytes)"

    companion object {
        /** The app's own bound on a pause between two frames of one BLE reply. The contract states none. */
        val INTER_FRAME: Duration = 20.seconds
        val DEFAULT_FIRST_FRAME: Duration = 15.seconds

        /** OPEN finalizes and checksums, FLUSH writes Parquet, REBOOT stops tasks first. */
        val SLOW_FIRST_FRAME: Duration = 60.seconds

        /** WIFI_SCAN answers after the radio sweep. */
        val SCAN_FIRST_FRAME: Duration = 20.seconds
        val WRITE_DEADLINE: Duration = 10.seconds

        /**
         * The firmware contract's LAN transport tells a phone to allow at least 30 s for a reply
         * (`ble-sync-protocol.md`, "LAN transport"). On LAN this is the floor for the first frame
         * and the wait between frames.
         */
        val LAN_REPLY_WAIT: Duration = 30.seconds

        /** The spec for [request] on [kind]. LAN raises both waits to at least [LAN_REPLY_WAIT]. */
        fun of(request: Request, kind: TransportKind, drainBound: DrainBound = DrainBound()): RequestSpec {
            val first = when (request.opcode) {
                Opcode.OPEN, Opcode.FLUSH, Opcode.REBOOT -> SLOW_FIRST_FRAME
                Opcode.WIFI_SCAN -> SCAN_FIRST_FRAME
                else -> DEFAULT_FIRST_FRAME
            }
            val lan = kind == TransportKind.LAN
            return RequestSpec(
                op = request.opcode,
                body = request.encode(),
                firstFrameTimeout = if (lan) maxOf(first, LAN_REPLY_WAIT) else first,
                interFrameTimeout = if (lan) maxOf(INTER_FRAME, LAN_REPLY_WAIT) else INTER_FRAME,
                // STATUS is answered by a push, never by a reply frame.
                expectsResponse = request.opcode != Opcode.STATUS,
                drainBound = drainBound,
            )
        }
    }
}
