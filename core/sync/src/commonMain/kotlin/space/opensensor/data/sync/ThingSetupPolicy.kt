package space.opensensor.data.sync

import space.opensensor.data.protocol.Advertisement
import space.opensensor.data.protocol.DeviceConfig
import space.opensensor.data.protocol.ThingMobility
import space.opensensor.model.H3Cell

/**
 * The one rule both phones use to decide that a Thing still needs setup (#272). The board derives the
 * same state (firmware #147), a fixed Thing with an empty `location.cell`.
 *
 * - Advert flags bit 6 `setup` set means setup is needed. CoreS3 `v6.24` and Waveshare `v2.18` set it.
 *   Older firmware leaves it at 0.
 * - Otherwise CONFIG page 1 decides. When it carries `thing.setup`, that value is the answer, so 0 means
 *   no setup even with an empty cell.
 * - Without `thing.setup` (older firmware) the rule is derived. A Thing that says `fixed`, or says no
 *   mobility while the page reports a `location` object, needs setup until `location.cell` holds an H3
 *   cell. Any valid cell counts, at any resolution, as the board clears the bit for any stored cell.
 * - A `moving` Thing never needs a cell. A mobility this build does not know claims nothing.
 * - Without bit 6 and without page 1 (not read yet, or firmware without page 1) nothing is claimed.
 *
 * A caller passes advert flags only from an advert newer than its last page 1 read. Right after a
 * save, a cached advert can still carry bit 6 while a fresh page 1 already says setup is done. The
 * wizard confirms completion by reading page 1 `thing.setup` = 0 after its SET_CONFIG.
 */
object ThingSetupPolicy {
    /**
     * Whether the Thing needs setup, from its advert [advertFlags] byte and its page 1 [config]. Either
     * may be null when the phone has not seen it.
     */
    fun needsSetup(advertFlags: Int?, config: DeviceConfig?): Boolean {
        if (advertFlags != null && advertFlags and Advertisement.FLAG_SETUP != 0) return true
        if (config == null) return false
        config.thing?.needsSetup?.let { return it }
        val mobility = config.thing?.mobility
        val fixed = when {
            mobility == null -> config.location != null
            else -> ThingMobility.fromWire(mobility) == ThingMobility.FIXED
        }
        return fixed && !hasCell(config)
    }

    private fun hasCell(config: DeviceConfig): Boolean = config.location?.cell?.let { H3Cell.isCellHex(it) } == true
}
