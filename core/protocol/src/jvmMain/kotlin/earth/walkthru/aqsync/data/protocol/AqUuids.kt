package earth.walkthru.aqsync.data.protocol

import java.util.UUID

/**
 * GATT attribute identities from docs/ble-sync-protocol.md (protocol v2; the GATT layout is
 * unchanged from v1).
 *
 * Base UUID `255f595e-XXXX-47ed-8e0d-59994199b680`; the 16-bit field selects the attribute.
 */
object AqUuids {
    /** Highest protocol version this app implements. v1 devices are a strict subset. */
    const val PROTOCOL_VERSION = AqGatt.PROTOCOL_VERSION

    /** Service "OpenSensor Sync". Advertised as the complete 128-bit service UUID. */
    val SERVICE: UUID = UUID.fromString(AqGatt.SERVICE)

    /** JSON, ≤ 400 bytes, static for a boot. Read (may need a long read). */
    val INFO: UUID = UUID.fromString(AqGatt.INFO)

    /** JSON, ≤ 240 bytes. Read + notify after every stored sample and state change. */
    val STATUS: UUID = UUID.fromString(AqGatt.STATUS)

    /** JSON, ≤ 240 bytes. Read + notify once per sample. */
    val LIVE: UUID = UUID.fromString(AqGatt.LIVE)

    /** Binary request, ≤ 512 bytes. Write with response. */
    val CONTROL: UUID = UUID.fromString(AqGatt.CONTROL)

    /** Binary response frames, ≤ payload_max. Notify. */
    val RESPONSE: UUID = UUID.fromString(AqGatt.RESPONSE)

    /** Client Characteristic Configuration Descriptor. */
    val CCCD: UUID = UUID.fromString(AqGatt.CCCD)

    /** Device accepts up to 517; the phone MUST request ≥ 247 before using `control`. */
    const val MTU_REQUEST = 517
    const val MTU_MINIMUM = 247
}
