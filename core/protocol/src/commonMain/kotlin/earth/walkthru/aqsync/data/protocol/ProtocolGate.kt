package earth.walkthru.aqsync.data.protocol

/** Outcome of the shared protocol check both phones run on a device's `info`. */
sealed interface ProtoGate {
    /** The device speaks protocol 3 or newer. */
    data class Accepted(val proto: Int) : ProtoGate

    /** The device reports an older protocol, or none. [reported] is null when `proto` is absent. */
    data class UpdateFirmware(val reported: Int?, val required: Int) : ProtoGate
}

/**
 * Accepts `proto` at or above [AqGatt.PROTOCOL_VERSION]. Older or missing versions need a firmware
 * update, since proto 2 frames such as the 13-byte `LIST_END` no longer decode.
 */
fun requireProto3(info: DeviceInfo): ProtoGate {
    val proto = info.proto
    return if (proto != null && proto >= AqGatt.PROTOCOL_VERSION) {
        ProtoGate.Accepted(proto)
    } else {
        ProtoGate.UpdateFirmware(proto, AqGatt.PROTOCOL_VERSION)
    }
}
