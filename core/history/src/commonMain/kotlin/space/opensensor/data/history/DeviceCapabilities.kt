package space.opensensor.data.history

import space.opensensor.data.protocol.DeviceInfo
import space.opensensor.data.protocol.LiveReading
import space.opensensor.data.protocol.LiveSample
import space.opensensor.data.protocol.PmsStatus
import space.opensensor.model.BoardTemplate
import space.opensensor.model.BundledCatalog
import space.opensensor.model.Capability
import space.opensensor.model.Family
import space.opensensor.model.Role
import space.opensensor.model.Thing
import space.opensensor.model.ThingDatastream

/**
 * What the connected device can do, derived from `INFO` as a set of [Capability] ids. Screens ask
 * these questions instead of comparing schema strings or key names. Every answer comes from the
 * bundled template and app profile of `info.dict`, or from the template the board served (#191), so a
 * new board is a template and a profile, not a code change. Pure and shared by Android and iOS.
 *
 * - [thing] is the served or bundled template joined with its app profile. It reads LIVE
 *   ([reading]). Null without `INFO`, without a dictionary or for a board whose template is neither
 *   served nor bundled, which has no LIVE view.
 * - [Capability.LIVE] is a resolved [thing].
 * - [Capability.CONFIG] is protocol v2 or later: pairing, Wi-Fi, LAN and bonds can be edited.
 * - [Capability.SENSOR_IDENTITY] is a template with the `sensor_serial_code` column, a board that
 *   stores the fitted PM module's sticker (`sensor` config) and can opt in to a batch estimate.
 * - [Capability.BATCH_ESTIMATE] is a template that writes the unvalidated Plantower batch estimate
 *   column, the only boards whose live view may show it (still gated by the board's opt-in, see
 *   [showsBatchEstimate]).
 * - [Capability.PARTICLES] is an `air` profile whose LIVE shows a physical PM2.5 on the PM2.5 scale,
 *   see [showsAirCards].
 */
data class DeviceCapabilities(
    val thing: Thing?,
    val capabilities: Set<Capability>,
) {
    operator fun contains(capability: Capability): Boolean = capability in capabilities

    val configurable: Boolean get() = Capability.CONFIG in capabilities
    val sensorIdentity: Boolean get() = Capability.SENSOR_IDENTITY in capabilities
    val batchEstimate: Boolean get() = Capability.BATCH_ESTIMATE in capabilities

    /** [sample] read through [thing], or null without both. */
    fun reading(sample: LiveSample?): LiveReading? = LiveReading.of(sample, thing)

    /**
     * The particle cards (mood, PM2.5) apply with [Capability.PARTICLES], once a sample carries the PM
     * sensor state. A sample without the `pms_status` key (the board has no such field) or with status
     * ABSENT does not. A `null` status is no reading now, never a missing sensor, so an overflow sample
     * keeps the particle cards with their values cleared.
     */
    fun showsAirCards(sample: LiveSample?): Boolean {
        if (Capability.PARTICLES !in capabilities) return false
        val reading = LiveReading.of(sample, thing) ?: return false
        return reading.carries(AirMetrics.PMS_STATUS) && reading.pmsStatus != PmsStatus.ABSENT
    }

    /** The temperature LIVE shows, null when it shows none. */
    val temperature: ThingDatastream? get() = thing?.live?.temperature?.let { thing.datastream(it.name) }

    /** The humidity LIVE shows, null when it shows none. */
    val humidity: ThingDatastream? get() = thing?.live?.humidity?.let { thing.datastream(it.name) }

    /** True when the LIVE temperature is the air around the board, a physical measurement, not the board's own. */
    val ambientTemperature: Boolean get() = temperature?.datastream?.role == Role.PHYSICAL_MEASUREMENT

    /** The batch estimate card: a bundled template with the column and the board's acknowledged opt-in (`1`). */
    fun showsBatchEstimate(boardBatchCandidate: Int?): Boolean = batchEstimate && boardBatchCandidate == 1

    companion object {
        /** The column that holds the fitted PM module's sticker serial. */
        const val SENSOR_SERIAL_COLUMN = "sensor_serial_code"

        /** Nothing known yet (no `INFO`): no live interpretation, no configuration. */
        val NONE = DeviceCapabilities(thing = null, capabilities = emptySet())

        /** Resolves `info.dict` and `info.desc` against [served] templates first, then the bundled ones. */
        fun from(info: DeviceInfo?, served: List<BoardTemplate> = ServedTemplates.shared.all.value): DeviceCapabilities {
            if (info == null) return NONE
            val thing = info.dict?.let { BundledCatalog.thing(info.thing.orEmpty(), it, desc = info.desc, served = served) }
            return DeviceCapabilities(thing, capabilitiesOf(thing, proto = info.proto ?: 1))
        }

        /** The capabilities of [thing] on a board speaking sync protocol [proto]. */
        fun capabilitiesOf(thing: Thing?, proto: Int): Set<Capability> = buildSet {
            if (proto >= 2) add(Capability.CONFIG)
            if (thing == null) return@buildSet
            add(Capability.LIVE)
            if (thing.datastream(SENSOR_SERIAL_COLUMN) != null) add(Capability.SENSOR_IDENTITY)
            if (thing.datastream(AirMetrics.BATCH_ESTIMATE_COLUMN) != null) add(Capability.BATCH_ESTIMATE)
            if (thing.profile.family == Family.AIR && thing.showsPm25Live()) add(Capability.PARTICLES)
        }

        /** The profile scale id of PM2.5. */
        private const val PM25_SCALE = "pm25"

        /** A physical PM2.5 measurement on the PM2.5 scale that LIVE shows. */
        private fun Thing.showsPm25Live(): Boolean = datastreams.any {
            it.scaleId == PM25_SCALE && it.datastream.role == Role.PHYSICAL_MEASUREMENT && it.datastream.liveKey in live.keys
        }
    }
}
