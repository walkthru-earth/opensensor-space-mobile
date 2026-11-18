package space.opensensor

import space.opensensor.data.ble.ConnectionState

/**
 * The link state the settings and Files pages of one sensor show on this phone. A pairing the
 * sensor lost outranks a pending connect, so the page names the repair instead of Disconnected.
 */
internal object SensorLink {
    /** The technical reason behind a lost bond. The pages show their own repair words for it. */
    const val PAIRING_REPAIR = "BOND_MISSING"

    fun state(ready: Boolean, pairingLost: Boolean, connecting: Boolean): ConnectionState = when {
        ready -> ConnectionState.Ready(0)
        pairingLost -> ConnectionState.Failed(PAIRING_REPAIR)
        connecting -> ConnectionState.Connecting
        else -> ConnectionState.Disconnected
    }

    /** Whether [state] is the lost pairing, so the banner offers the Bluetooth settings. */
    fun needsRepair(state: ConnectionState): Boolean = state is ConnectionState.Failed && state.reason == PAIRING_REPAIR
}
