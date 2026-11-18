package space.opensensor.family.air

import space.opensensor.data.history.MetricCatalog
import space.opensensor.data.history.MetricSpec
import space.opensensor.data.history.Metrics
import space.opensensor.data.history.stateOf
import space.opensensor.data.protocol.LiveReading
import space.opensensor.model.SensorState

/**
 * The air family's named metrics, the columns of the bundled air boards that the air cards and their
 * tests name. Each one resolves from [MetricCatalog.shared] on every read, so it follows the families the
 * app installed ([Metrics.install]) and a catalog without it never fails to load. Generic code reads
 * [Metrics] and never these. The derived ones exist once [AirFamily] is installed.
 */
object AirMetrics {
    /** The unvalidated Plantower batch estimate column. */
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
 * The particle sensor state, the state of the status column that gates the atmospheric PM2.5
 * (`pms_status` on a Plantower board). Null when the sample has no reading of it now.
 */
val LiveReading.pmState: SensorState? get() = stateOf(AirFamily.PM25_ATMOSPHERIC)

/** True when the particle sensor reports a valid reading now. */
val LiveReading.pmValid: Boolean get() = pmState?.valid == true
