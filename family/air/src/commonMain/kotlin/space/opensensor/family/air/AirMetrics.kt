package space.opensensor.family.air

import space.opensensor.data.history.MetricCatalog
import space.opensensor.data.history.MetricSpec
import space.opensensor.data.history.Metrics
import space.opensensor.data.history.stateOf
import space.opensensor.data.protocol.LiveReading
import space.opensensor.model.Basis
import space.opensensor.model.Datastream
import space.opensensor.model.SensorState
import space.opensensor.model.Thing

/**
 * The air metrics of one Thing, each found by `opensensor:property` ([AirColumns]) and taken from the
 * Thing's own catalog ([MetricCatalog.of]), so a new board with the air properties needs no app change.
 * Screens and flows read [pm25], [pm25Corrected] and [of].
 *
 * The named metrics below ([PM25], [PM25_CORRECTED] and the rest) are the bundled CoreS3 and Waveshare
 * columns, for tests, previews and the benchmark. Each resolves from [MetricCatalog.shared] on every
 * read, so it follows the families the app installed ([Metrics.install]). Generic code reads [Metrics]
 * and never these.
 */
object AirMetrics {
    /** The stored PM2.5 metric of [thing], or null for a thing without one. */
    fun pm25(thing: Thing): MetricSpec? = of(thing, thing.air?.pm25)

    /** The corrected view of [thing]'s PM2.5 (#92), the derived metric of its property, or null when it has none. */
    fun pm25Corrected(thing: Thing): MetricSpec? {
        val stored = pm25(thing) ?: return null
        return MetricCatalog.of(thing).firstOrNull { it.derived && it.basis == Basis.CORRECTED && it.property == stored.property }
    }

    /** The stored metric of [column] on [thing], or null without [column] or for a column the profile hides. */
    fun of(thing: Thing, column: Datastream?): MetricSpec? {
        val name = column?.name ?: return null
        return MetricCatalog.of(thing).firstOrNull { !it.derived && it.column == name }
    }

    /** The bundled batch estimate column. */
    const val BATCH_ESTIMATE_COLUMN = "pm25_batch_candidate_ug_m3"

    /** PM2.5 with the US EPA humidity correction, from the stored CF=1 PM2.5 and RH, row by row. Nothing stored changes. */
    val PM25_CORRECTED: MetricSpec get() = named(AirFamily.PM25_CORRECTED_ID)

    /** The sensor's own atmospheric PM2.5, as stored. */
    val PM25: MetricSpec get() = named(AirFamily.PM25_ATMOSPHERIC)
    val PM10: MetricSpec get() = named(AirFamily.PM10_ATMOSPHERIC)
    val PM1: MetricSpec get() = named(AirFamily.PM1_ATMOSPHERIC)

    /** Optional Plantower batch equation, gated separately and never used as the primary PM2.5 reading. */
    val PM25_BATCH_ESTIMATE: MetricSpec get() = named(BATCH_ESTIMATE_COLUMN)
    val PARTICLES_GT03: MetricSpec get() = named("particles_gt03_per_01l")
    val PARTICLES_GT25: MetricSpec get() = named("particles_gt25_per_01l")

    /** Null on CoreS3 (SHT20 address collision), written by the Waveshare PMS5003T. A null RH leaves PM2.5 raw. */
    val AMBIENT_TEMP: MetricSpec get() = named("ambient_temperature_c")
    val HUMIDITY: MetricSpec get() = named(AirFamily.RELATIVE_HUMIDITY)

    private fun named(id: String): MetricSpec = requireNotNull(MetricCatalog.shared.byId(id)) { "no bundled template has $id" }
}

/**
 * The particle sensor state, the state of the status column that gates the thing's PM2.5
 * (`pms_status` on a Plantower board, `sen6x_status` on a Sensirion one). Null when the thing has no
 * PM2.5 or the sample has no reading of its status now.
 */
val LiveReading.pmState: SensorState? get() = thing.air?.let { stateOf(it.pm25.name) }

/** True when the particle sensor reports a valid reading now. */
val LiveReading.pmValid: Boolean get() = pmState?.valid == true
