package space.opensensor.data.lan

import space.opensensor.data.DeviceContinuityPolicy
import space.opensensor.data.protocol.DeviceGatt
import space.opensensor.data.protocol.DeviceInfo
import space.opensensor.data.protocol.Frame
import space.opensensor.data.protocol.LanProtocol
import space.opensensor.data.protocol.ProtoGate
import space.opensensor.data.protocol.requireProto3
import space.opensensor.link.Heartbeat
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The one reading of the frame that answers the LAN handshake (`"OSS1"` + token), and the LAN
 * limits, shared by Android `TcpLinkDriver` and iOS `LanSession` (through `SyncWire`).
 *
 * ERROR and BYE refusals are read before this, by each transport's typed refusal path. Identity
 * goes through [DeviceContinuityPolicy] as a connection that is not authenticated Bluetooth, so
 * HELLO never enrolls a device or accepts a new thing.
 */
object LanHandshake {
    /**
     * TCP connect bound, the same on both phones. A connect on a LAN takes milliseconds, so a slower
     * one is a stale mDNS record or a sleeping radio, and Bluetooth is the faster way on.
     */
    val CONNECT_TIMEOUT: Duration = 5.seconds

    /**
     * From the token write to the framed answer, the same on both phones. The device checks the
     * token and answers at once. 8 s leaves room for a device busy writing its card.
     */
    val HELLO_TIMEOUT: Duration = 8.seconds

    /** Smallest `payload_max` a HELLO may declare. A frame must hold a header and a useful chunk. */
    const val MIN_PAYLOAD_MAX = 64

    /** Largest `payload_max`, the firmware contract's 1024. */
    const val PAYLOAD_MAX = LanProtocol.PAYLOAD_MAX

    /** Phone keepalive after this long without traffic, inside the device's idle limit. */
    val KEEPALIVE_AFTER_IDLE: Duration get() = Heartbeat.LAN.pingAfterIdle

    /** The link closes after this long without a received byte. */
    val IDLE_BOUND: Duration get() = Heartbeat.LAN.deadline

    /** How long `readStatus` waits for the STATUS push it asked for. */
    val STATUS_PUSH_WAIT: Duration = 10.seconds

    /** How long `readLive` waits for the next LIVE push. */
    val LIVE_PUSH_WAIT: Duration = 15.seconds

    sealed interface Verdict {
        data class Accepted(val hello: Frame.Hello, val info: DeviceInfo) : Verdict

        /** Not a HELLO. */
        data object WrongKind : Verdict

        /** HELLO or its info reports a protocol below 3, or none ([reported] null). */
        data class OldProto(val reported: Int?) : Verdict

        /** `payload_max` outside [MIN_PAYLOAD_MAX]..[PAYLOAD_MAX]. */
        data class PayloadCapOutOfRange(val payloadMax: Int) : Verdict

        /** Info that does not parse, or a missing or malformed thing or hardware id. */
        data object BadInfo : Verdict

        /** The hardware id is missing or differs from the saved one. */
        data object DeviceChanged : Verdict

        /** A thing other than the saved one. Only an authenticated Bluetooth connection accepts that. */
        data object ThingChanged : Verdict
    }

    /** The checks that need no saved identity, in order. Android's driver runs these. */
    fun check(frame: Frame): Verdict {
        if (frame !is Frame.Hello) return Verdict.WrongKind
        if (frame.proto < DeviceGatt.PROTOCOL_VERSION) return Verdict.OldProto(frame.proto)
        if (frame.payloadMax !in MIN_PAYLOAD_MAX..PAYLOAD_MAX) return Verdict.PayloadCapOutOfRange(frame.payloadMax)
        val info = runCatching { DeviceInfo.parse(frame.infoJson) }.getOrNull() ?: return Verdict.BadInfo
        val gate = requireProto3(info)
        if (gate is ProtoGate.UpdateFirmware) return Verdict.OldProto(gate.reported)
        return Verdict.Accepted(frame, info)
    }

    /**
     * [check], then the saved identity through [DeviceContinuityPolicy]. LAN has no endpoint of
     * its own, so both sides carry the same placeholder address and only hardware and thing count.
     */
    fun verify(frame: Frame, savedDevice: String, savedThing: String): Verdict {
        val verdict = check(frame)
        if (verdict !is Verdict.Accepted) return verdict
        val expected = DeviceContinuityPolicy.Identity(LAN_ENDPOINT, savedDevice, savedThing)
        val observed = DeviceContinuityPolicy.Identity(LAN_ENDPOINT, verdict.info.dev, verdict.info.thing)
        return when (val decision = DeviceContinuityPolicy.evaluate(expected, observed, authenticatedBle = false)) {
            is DeviceContinuityPolicy.Decision.Accept -> verdict
            is DeviceContinuityPolicy.Decision.Reject -> when (decision.reason) {
                DeviceContinuityPolicy.Reason.HARDWARE_CHANGED, DeviceContinuityPolicy.Reason.HARDWARE_MISSING -> Verdict.DeviceChanged
                DeviceContinuityPolicy.Reason.THING_CHANGE_REQUIRES_BLE, DeviceContinuityPolicy.Reason.THING_CHANGE_REQUIRES_HARDWARE -> Verdict.ThingChanged
                else -> Verdict.BadInfo
            }
        }
    }

    private const val LAN_ENDPOINT = "lan"
}
