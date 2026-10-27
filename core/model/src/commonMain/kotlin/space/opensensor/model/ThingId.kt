package space.opensensor.model

/**
 * How the app reaches one Thing, which collections, keep alive leases, the sync worker's input and
 * the iOS bridges name in place of a bare address string (#223). The Thing's lasting identity is its
 * UUID, the `thing_source` row and the `archive/thing=<uuid>/` path. A [ThingId] only says where the
 * phone collects it from.
 */
sealed class ThingId {
    /** A stable text form, `ble:<address>` or `phone:<uuid>`, that [parse] reads back. */
    abstract val key: String

    /**
     * A board the phone collects over Bluetooth, or over LAN found through it. [address] is the BLE
     * address on Android and the board's 12 hex `dev` on iOS, kept exactly as the platform gave it.
     */
    data class BleAddress(val address: String) : ThingId() {
        init {
            require(address.isNotBlank()) { "a Bluetooth address is not blank" }
        }

        override val key: String get() = BLE + address

        override fun toString(): String = key
    }

    /** This phone's own install, a Thing that records on the phone with [uuid] as its Thing UUID. */
    data class PhoneInstall(val uuid: String) : ThingId() {
        init {
            require(uuid.isNotBlank()) { "a Thing UUID is not blank" }
        }

        override val key: String get() = PHONE + uuid

        override fun toString(): String = key
    }

    companion object {
        private const val BLE = "ble:"
        private const val PHONE = "phone:"

        /** The [ThingId] of a [key], or null when it is not one. */
        fun parse(key: String): ThingId? = when {
            key.startsWith(BLE) -> key.removePrefix(BLE).takeIf { it.isNotBlank() }?.let(::BleAddress)
            key.startsWith(PHONE) -> key.removePrefix(PHONE).takeIf { it.isNotBlank() }?.let(::PhoneInstall)
            else -> null
        }
    }
}
