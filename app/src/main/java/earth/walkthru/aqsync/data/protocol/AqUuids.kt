package earth.walkthru.aqsync.data.protocol

import java.util.UUID

/**
 * GATT attribute identities from docs/ble-sync-protocol.md (protocol v1).
 *
 * Base UUID `c0a5e9f0-XXXX-4b1a-9c3e-2d7f8a6b4e01`; the 16-bit field selects the attribute.
 */
object AqUuids {
    const val PROTOCOL_VERSION = 1

    private fun aq(attr: Int): UUID = UUID.fromString(
        "c0a5e9f0-%04x-4b1a-9c3e-2d7f8a6b4e01".format(attr)
    )

    /** Service "AQ Sync". Advertised as the complete 128-bit service UUID. */
    val SERVICE: UUID = aq(0x0001)

    /** JSON, ≤ 400 bytes, static for a boot. Read (may need a long read). */
    val INFO: UUID = aq(0x0002)

    /** JSON, ≤ 240 bytes. Read + notify after every stored sample and state change. */
    val STATUS: UUID = aq(0x0003)

    /** JSON, ≤ 240 bytes. Read + notify once per sample. */
    val LIVE: UUID = aq(0x0004)

    /** Binary request, ≤ 512 bytes. Write with response. */
    val CONTROL: UUID = aq(0x0005)

    /** Binary response frames, ≤ payload_max. Notify. */
    val RESPONSE: UUID = aq(0x0006)

    /** Client Characteristic Configuration Descriptor. */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Device accepts up to 517; the phone MUST request ≥ 247 before using `control`. */
    const val MTU_REQUEST = 517
    const val MTU_MINIMUM = 247

    /** Local name prefix; `xxxx` is the last four hex digits of `device_id`. */
    const val NAME_PREFIX = "AQ-"
}
