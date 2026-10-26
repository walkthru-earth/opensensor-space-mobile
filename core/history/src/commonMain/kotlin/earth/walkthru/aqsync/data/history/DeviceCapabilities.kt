package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.data.protocol.DeviceInfo
import earth.walkthru.aqsync.data.protocol.LiveReading
import earth.walkthru.aqsync.data.protocol.LiveSample
import earth.walkthru.aqsync.data.protocol.PmsStatus
import space.opensensor.model.BundledCatalog
import space.opensensor.model.Family
import space.opensensor.model.Role
import space.opensensor.model.Station
import space.opensensor.model.StationDatastream

/**
 * What the connected device can do, derived from `INFO`. Screens ask these questions instead of
 * comparing schema strings or key names. Every answer comes from the bundled template and app
 * profile of `info.dict`, so a new board is a template and a profile, not a code change. Pure and
 * shared by Android and iOS.
 *
 * - [station] is the bundled template joined with its app profile. It reads LIVE ([reading]). Null
 *   without `INFO`, without a dictionary or for one the app does not bundle, which has no LIVE view.
 * - [configurable] is protocol v2 or later: pairing, Wi-Fi, LAN and bonds can be edited.
 * - [sensorIdentity] is a template with the `sensor_serial_code` column, a board that stores the
 *   fitted PM module's sticker (`sensor` config) and can opt in to a batch estimate.
 * - [batchEstimate] is a template that writes the unvalidated Plantower batch estimate column, the
 *   only boards whose live view may show it (still gated by the board's opt-in, see
 *   [showsBatchEstimate]).
 */
data class DeviceCapabilities(
    val station: Station?,
    val configurable: Boolean,
    val sensorIdentity: Boolean,
    val batchEstimate: Boolean,
) {
    /** [sample] read through [station], or null without both. */
    fun reading(sample: LiveSample?): LiveReading? = LiveReading.of(sample, station)

    /**
     * The particle reading (mood, PM2.5) applies to an `air` profile whose LIVE shows PM2.5, once a
     * sample carries the PM sensor state. A sample without the `pms_status` key (the board has no such
     * field) or with status ABSENT does not. A `null` status is no reading now, never a missing sensor,
     * so an overflow sample keeps the particle cards with their values cleared.
     */
    fun showsAirCards(sample: LiveSample?): Boolean {
        val station = station ?: return false
        if (station.profile.family != Family.AIR || !station.showsPm25Live()) return false
        val reading = LiveReading.of(sample, station) ?: return false
        return reading.carries(LiveReading.PMS_STATUS) && reading.pmsStatus != PmsStatus.ABSENT
    }

    /** The temperature LIVE shows, null when it shows none. */
    val temperature: StationDatastream? get() = station?.live?.temperature?.let { station.datastream(it.name) }

    /** The humidity LIVE shows, null when it shows none. */
    val humidity: StationDatastream? get() = station?.live?.humidity?.let { station.datastream(it.name) }

    /** True when the LIVE temperature is the air around the board, a physical measurement, not the board's own. */
    val ambientTemperature: Boolean get() = temperature?.datastream?.role == Role.PHYSICAL_MEASUREMENT

    /** The batch estimate card: a bundled template with the column and the board's acknowledged opt-in (`1`). */
    fun showsBatchEstimate(boardBatchCandidate: Int?): Boolean = batchEstimate && boardBatchCandidate == 1

    companion object {
        /** The column that holds the fitted PM module's sticker serial. */
        const val SENSOR_SERIAL_COLUMN = "sensor_serial_code"

        /** Nothing known yet (no `INFO`): no live interpretation, no configuration. */
        val NONE = DeviceCapabilities(station = null, configurable = false, sensorIdentity = false, batchEstimate = false)

        fun from(info: DeviceInfo?): DeviceCapabilities {
            if (info == null) return NONE
            val station = info.dict?.let { BundledCatalog.station(info.station.orEmpty(), it) }
            return DeviceCapabilities(
                station = station,
                configurable = (info.proto ?: 1) >= 2,
                sensorIdentity = station?.datastream(SENSOR_SERIAL_COLUMN) != null,
                batchEstimate = station?.datastream(Metrics.PM25_BATCH_ESTIMATE.column) != null,
            )
        }

        /** The profile scale id of PM2.5. */
        private const val PM25_SCALE = "pm25"

        /** A physical PM2.5 measurement on the PM2.5 scale that LIVE shows. */
        private fun Station.showsPm25Live(): Boolean = datastreams.any {
            it.scaleId == PM25_SCALE && it.datastream.role == Role.PHYSICAL_MEASUREMENT && it.datastream.liveKey in live.keys
        }
    }
}
