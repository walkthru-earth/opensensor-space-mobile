package space.opensensor.data.protocol

import java.util.UUID

/**
 * GATT attribute identities from the firmware repository's `docs/shared/ble-sync-protocol.md`.
 *
 * Base UUID `255f595e-XXXX-47ed-8e0d-59994199b680`; the 16-bit field selects the attribute.
 */
object DeviceUuids {
    /** Highest protocol version this app implements. A link below proto 3 is closed. */
    const val PROTOCOL_VERSION = DeviceGatt.PROTOCOL_VERSION

    /** Service "OpenSensor Sync". Advertised as the complete 128-bit service UUID. */
    val SERVICE: UUID = UUID.fromString(DeviceGatt.SERVICE)

    /** JSON, ≤ 399 bytes, static for a boot. Read (may need a long read). */
    val INFO: UUID = UUID.fromString(DeviceGatt.INFO)

    /** JSON, ≤ 480 bytes. Read + notify after every stored sample and state change. */
    val STATUS: UUID = UUID.fromString(DeviceGatt.STATUS)

    /** JSON, ≤ 480 bytes. Read + notify once per sample. */
    val LIVE: UUID = UUID.fromString(DeviceGatt.LIVE)

    /** Binary request, ≤ 512 bytes. Write with response. */
    val CONTROL: UUID = UUID.fromString(DeviceGatt.CONTROL)

    /** Binary response frames, ≤ payload_max. Notify. */
    val RESPONSE: UUID = UUID.fromString(DeviceGatt.RESPONSE)

    /** Client Characteristic Configuration Descriptor. */
    val CCCD: UUID = UUID.fromString(DeviceGatt.CCCD)

    /** Device accepts up to 517; the phone MUST request ≥ 247 before using `control`. */
    const val MTU_REQUEST = 517
    const val MTU_MINIMUM = 247
}
