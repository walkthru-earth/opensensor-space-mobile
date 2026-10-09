package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.data.protocol.DeviceInfo
import earth.walkthru.aqsync.data.protocol.LiveProfile
import earth.walkthru.aqsync.data.protocol.LiveProfiles
import earth.walkthru.aqsync.data.protocol.LiveSample
import earth.walkthru.aqsync.data.protocol.PmsStatus

/**
 * What the connected device can do, derived once from `INFO`. Screens ask these questions instead
 * of comparing schema strings, so a new firmware family changes one mapping and its test, not every
 * composable. Pure and shared by Android and iOS.
 *
 * - [liveProfile] is the reviewed interpretation of LIVE fields ([LiveProfiles.resolve]); null for
 *   an unknown schema or dictionary, which still syncs verified files.
 * - [configurable] is protocol v2 or later: pairing, Wi-Fi, LAN and bonds can be edited.
 * - [sensorIdentity] is the Waveshare telemetry family, whose board stores the fitted PM module's
 *   sticker (`sensor` config) and can opt in to a batch estimate.
 * - [batchEstimate] is the reviewed Waveshare v2/v3 dictionaries, the only ones whose live view may show
 *   the unvalidated Plantower batch estimate (still gated by the board's opt-in, see
 *   [showsBatchEstimate]).
 */
data class DeviceCapabilities(
    val liveProfile: LiveProfile?,
    val configurable: Boolean,
    val sensorIdentity: Boolean,
    val batchEstimate: Boolean,
) {
    /**
     * The particle reading (mood, PM2.5) applies when the profile is reviewed and the live sample
     * reports a PM sensor at all. A sample without the `pms` key or with status ABSENT does not.
     */
    fun showsParticles(live: LiveSample?): Boolean =
        liveProfile != null && live?.pms != null && live.pms != PmsStatus.ABSENT.code

    /** The batch estimate card: the reviewed dictionary and the board's acknowledged opt-in (`1`). */
    fun showsBatchEstimate(boardBatchCandidate: Int?): Boolean = batchEstimate && boardBatchCandidate == 1

    companion object {
        const val WAVESHARE_SCHEMA_PREFIX = "waveshare-sim7670g-telemetry-"
        const val WAVESHARE_V2_SCHEMA = "waveshare-sim7670g-telemetry-v2"

        const val WAVESHARE_V3_SCHEMA = "waveshare-sim7670g-telemetry-v3"

        /** Nothing known yet (no `INFO`): no live interpretation, no configuration. */
        val NONE = DeviceCapabilities(liveProfile = null, configurable = false, sensorIdentity = false, batchEstimate = false)

        fun from(info: DeviceInfo?): DeviceCapabilities {
            if (info == null) return NONE
            val profile = LiveProfiles.resolve(info)
            val schema = info.schema
            return DeviceCapabilities(
                liveProfile = profile,
                configurable = (info.proto ?: 1) >= 2,
                sensorIdentity = schema?.startsWith(WAVESHARE_SCHEMA_PREFIX) == true,
                batchEstimate = profile != null && schema in setOf(WAVESHARE_V2_SCHEMA, WAVESHARE_V3_SCHEMA),
            )
        }
    }
}
