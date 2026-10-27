package space.opensensor.data.history

import space.opensensor.data.protocol.DeviceInfo
import space.opensensor.data.protocol.LiveReading
import space.opensensor.data.protocol.LiveSample
import space.opensensor.model.AppProfile
import space.opensensor.model.BoardTemplate
import space.opensensor.model.BundledCatalog
import space.opensensor.model.Capability
import space.opensensor.model.DerivedMetricProvider
import space.opensensor.model.Role
import space.opensensor.model.Thing
import space.opensensor.model.ThingDatastream

/**
 * What the connected device can do, derived from `INFO` as a set of [Capability] ids. Screens ask
 * these questions instead of comparing schema strings or key names. Every answer comes from the
 * bundled template and app profile of `info.dict`, or from the template the board served (#191), so a
 * new board is a template and a profile, not a code change. Pure and shared by Android and iOS.
 *
 * - [thing] is the served or bundled template joined with its reviewed app profile. It reads LIVE
 *   ([reading]). Null without `INFO`, without a dictionary, for a board whose template is neither
 *   served nor bundled, or for a dictionary with no reviewed profile. Such a board has no LIVE view,
 *   the LIVE key stability rule of `ble-sync-protocol.md`, so a derived profile never names a hero.
 * - [Capability.LIVE] is a resolved [thing].
 * - [Capability.CONFIG] is protocol v2 or later: pairing, Wi-Fi, LAN and bonds can be edited.
 * - [Capability.SENSOR_IDENTITY] is a template with the `sensor_serial_code` column, a board that
 *   stores the fitted sensor module's sticker (`sensor` config) and can opt in to a batch estimate.
 * - Every other capability is a family's ([DerivedMetricProvider.capabilitiesOf]), from the
 *   providers [Metrics] installed.
 */
data class DeviceCapabilities(
    val thing: Thing?,
    val capabilities: Set<Capability>,
) {
    operator fun contains(capability: Capability): Boolean = capability in capabilities

    val configurable: Boolean get() = Capability.CONFIG in capabilities
    val sensorIdentity: Boolean get() = Capability.SENSOR_IDENTITY in capabilities

    /** [sample] read through [thing], or null without both. */
    fun reading(sample: LiveSample?): LiveReading? = LiveReading.of(sample, thing)

    /** The temperature LIVE shows, null when it shows none. */
    val temperature: ThingDatastream? get() = thing?.live?.temperature?.let { thing.datastream(it.name) }

    /** The humidity LIVE shows, null when it shows none. */
    val humidity: ThingDatastream? get() = thing?.live?.humidity?.let { thing.datastream(it.name) }

    /** True when the LIVE temperature is the air around the board, a physical measurement, not the board's own. */
    val ambientTemperature: Boolean get() = temperature?.datastream?.role == Role.PHYSICAL_MEASUREMENT

    companion object {
        /** The column that holds the fitted sensor module's sticker serial. */
        const val SENSOR_SERIAL_COLUMN = "sensor_serial_code"

        /** Nothing known yet (no `INFO`): no live interpretation, no configuration. */
        val NONE = DeviceCapabilities(thing = null, capabilities = emptySet())

        /**
         * Resolves `info.dict` and `info.desc` against [served] templates first, then the bundled ones,
         * and joins the reviewed profile [profiles] gives for the dict. A template without one keeps
         * [Capability.SENSOR_IDENTITY] but has no [thing] and no LIVE.
         */
        fun from(
            info: DeviceInfo?,
            served: List<BoardTemplate> = ServedTemplates.shared.all.value,
            profiles: (String) -> AppProfile? = BundledCatalog::profile,
        ): DeviceCapabilities {
            if (info == null) return NONE
            val joined = info.dict?.let { BundledCatalog.thing(info.thing.orEmpty(), it, desc = info.desc, served = served, profile = profiles(it)) }
            val thing = joined?.takeUnless { it.profile.isDerived }
            return DeviceCapabilities(thing, capabilitiesOf(joined, proto = info.proto ?: 1))
        }

        /** The capabilities of [thing] on a board speaking sync protocol [proto], with the [providers]' own. */
        fun capabilitiesOf(thing: Thing?, proto: Int, providers: List<DerivedMetricProvider> = Metrics.providers): Set<Capability> = buildSet {
            if (proto >= 2) add(Capability.CONFIG)
            if (thing == null) return@buildSet
            if (thing.datastream(SENSOR_SERIAL_COLUMN) != null) add(Capability.SENSOR_IDENTITY)
            // LIVE and what it shows need a reviewed profile, a derived one only names columns.
            if (thing.profile.isDerived) return@buildSet
            add(Capability.LIVE)
            providers.forEach { addAll(it.capabilitiesOf(thing)) }
        }
    }
}
