package earth.walkthru.aqsync.link

import earth.walkthru.aqsync.data.protocol.Opcode
import earth.walkthru.aqsync.data.protocol.Request
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
        /** Proto 3 contract: a reply never pauses longer than this between frames. */
        val INTER_FRAME: Duration = 20.seconds
        val DEFAULT_FIRST_FRAME: Duration = 15.seconds

        /** OPEN finalizes and checksums, FLUSH writes Parquet, REBOOT stops tasks first. */
        val SLOW_FIRST_FRAME: Duration = 60.seconds

        /** WIFI_SCAN answers after the radio sweep. */
        val SCAN_FIRST_FRAME: Duration = 20.seconds
        val WRITE_DEADLINE: Duration = 10.seconds

        fun of(request: Request, drainBound: DrainBound = DrainBound()): RequestSpec = RequestSpec(
            op = request.opcode,
            body = request.encode(),
            firstFrameTimeout = when (request.opcode) {
                Opcode.OPEN, Opcode.FLUSH, Opcode.REBOOT -> SLOW_FIRST_FRAME
                Opcode.WIFI_SCAN -> SCAN_FIRST_FRAME
                else -> DEFAULT_FIRST_FRAME
            },
            // STATUS is answered by a push, never by a reply frame.
            expectsResponse = request.opcode != Opcode.STATUS,
            drainBound = drainBound,
        )
    }
}
