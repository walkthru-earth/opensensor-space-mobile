package space.opensensor.data.sync

import space.opensensor.data.protocol.Advertisement
import space.opensensor.data.protocol.DeviceConfig
import space.opensensor.model.SetupManifest

/**
 * The one rule both phones use to decide that a Thing still needs setup (#272).
 *
 * - Advert flags bit 6 `setup` set means setup is needed. The board's own answer is trusted.
 * - Otherwise CONFIG page 1 decides. `thing.setup` 1 means setup is needed.
 * - Then the [SetupPlan] of the board's [SetupManifest] and page 1. Setup is needed while a planned
 *   step is not done, a fixed Thing without a cell or a declared step whose `thing.setup_ack` bit is
 *   clear. A board that never writes the bits stays pending until a phone writes them.
 * - Without bit 6 and without page 1 (not read yet) nothing is claimed.
 *
 * A caller passes advert flags only from an advert newer than its last page 1 read. Right after a
 * save, a cached advert can still carry bit 6 while a fresh page 1 already says setup is done.
 */
object ThingSetupPolicy {
    /**
     * Whether the Thing needs setup, from its advert [advertFlags] byte, its page 1 [config] and the
     * [manifest] of its template. Either of the first two may be null when the phone has not seen it.
     */
    fun needsSetup(advertFlags: Int?, config: DeviceConfig?, manifest: SetupManifest): Boolean {
        if (advertFlags != null && advertFlags and Advertisement.FLAG_SETUP != 0) return true
        if (config == null) return false
        if (config.thing?.needsSetup == true) return true
        return SetupPlan.of(manifest, config).pending
    }
}
