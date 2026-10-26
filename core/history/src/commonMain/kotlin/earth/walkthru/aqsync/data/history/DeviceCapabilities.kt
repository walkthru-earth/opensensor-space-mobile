package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.data.protocol.DeviceInfo
import earth.walkthru.aqsync.data.protocol.LiveProfile
import earth.walkthru.aqsync.data.protocol.LiveProfiles
import earth.walkthru.aqsync.data.protocol.LiveSample
import earth.walkthru.aqsync.data.protocol.PmsStatus
import space.opensensor.model.BundledCatalog

/**
 * What the connected device can do, derived from `INFO`. Screens ask these questions instead of
 * comparing schema strings, so a new firmware family changes one mapping and its test, not every
 * composable. Pure and shared by Android and iOS.
 *
 * - [liveProfile] is the interpretation of LIVE fields ([LiveProfiles.resolve]), from the bundled
 *   template of the dictionary. Null without `INFO`, without a dictionary or for one the app does
 *   not bundle.
 * - [configurable] is protocol v2 or later: pairing, Wi-Fi, LAN and bonds can be edited.
 * - [sensorIdentity] is the Waveshare telemetry family, whose board stores the fitted PM module's
 *   sticker (`sensor` config) and can opt in to a batch estimate.
 * - [batchEstimate] is a bundled template that writes the unvalidated Plantower batch estimate
 *   column, the only boards whose live view may show it (still gated by the board's opt-in, see
 *   [showsBatchEstimate]).
 */
data class DeviceCapabilities(
    val liveProfile: LiveProfile?,
    val configurable: Boolean,
    val sensorIdentity: Boolean,
    val batchEstimate: Boolean,
) {
    /**
     * The particle reading (mood, PM2.5) applies when there is a LIVE profile and the live sample
     * reports a PM sensor at all. A sample without the `pms` key or with status ABSENT does not.
     */
    fun showsParticles(live: LiveSample?): Boolean =
        liveProfile != null && live?.pms != null && live.pms != PmsStatus.ABSENT.code

    /** The batch estimate card: a bundled template with the column and the board's acknowledged opt-in (`1`). */
    fun showsBatchEstimate(boardBatchCandidate: Int?): Boolean = batchEstimate && boardBatchCandidate == 1

    companion object {
        const val WAVESHARE_SCHEMA_PREFIX = "waveshare-sim7670g-telemetry-"

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
                batchEstimate = info.dict?.let(BundledCatalog::template)?.datastream(Metrics.PM25_BATCH_ESTIMATE.column) != null,
            )
        }
    }
}
