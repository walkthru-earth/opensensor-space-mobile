package space.opensensor.data.sync

import space.opensensor.data.protocol.Advertisement
import space.opensensor.data.protocol.DeviceConfig
import space.opensensor.data.protocol.ThingMobility
import space.opensensor.model.H3Cell

/**
 * The one rule both phones use to decide that a Thing still needs setup (#272). It mirrors the state
 * firmware #147 derives on the board, a fixed Thing with an empty `location.cell`.
 *
 * - Advert flags bit 6 `setup` set means setup is needed. No released firmware sets it yet.
 * - Otherwise CONFIG page 1 decides. A Thing that says `fixed`, or says no mobility while the page
 *   reports a `location` object, needs setup until `location.cell` holds an H3 cell.
 * - A `moving` Thing never needs a cell. A mobility this build does not know claims nothing.
 * - Without bit 6 and without page 1 (not read yet, or firmware without page 1) nothing is claimed.
 */
object ThingSetupPolicy {
    /**
     * Whether the Thing needs setup, from its advert [advertFlags] byte and its page 1 [config]. Either
     * may be null when the phone has not seen it.
     */
    fun needsSetup(advertFlags: Int?, config: DeviceConfig?): Boolean {
        if (advertFlags != null && advertFlags and Advertisement.FLAG_SETUP != 0) return true
        if (config == null) return false
        val mobility = config.thing?.mobility
        val fixed = when {
            mobility == null -> config.location != null
            else -> ThingMobility.fromWire(mobility) == ThingMobility.FIXED
        }
        return fixed && !hasCell(config)
    }

    private fun hasCell(config: DeviceConfig): Boolean = config.location?.cell?.let { H3Cell.isCellHex(it) } == true
}
