package space.opensensor.data.history

import space.opensensor.airquality.AirDerivedMetrics
import space.opensensor.data.protocol.LiveReading
import space.opensensor.data.protocol.PmsStatus

/**
 * The air family's named metrics, the columns of the bundled air boards that the air cards and their
 * tests name. Each one resolves from [Metrics.catalog] on first use, so a catalog without it never
 * fails to load, and generic code reads [Metrics] and never these. They live here, not in
 * `:core:airquality` with [AirDerivedMetrics], because [MetricSpec] is a History type.
 */
object AirMetrics {
    /** The PM sensor state column, `0` absent to `4` valid. */
    const val PMS_STATUS = "pms_status"

    /** The unvalidated Plantower batch estimate column. */
    const val BATCH_ESTIMATE_COLUMN = "pm25_batch_candidate_ug_m3"

    /** PM2.5 with the US EPA humidity correction, from the stored CF=1 PM2.5 and RH, row by row. Nothing stored changes. */
    val PM25_CORRECTED: MetricSpec by lazy { named(AirDerivedMetrics.PM25_CORRECTED_ID) }

    /** The sensor's own atmospheric PM2.5, as stored. */
    val PM25: MetricSpec by lazy { named(AirDerivedMetrics.PM25_ATMOSPHERIC) }
    val PM10: MetricSpec by lazy { named("pm10_atmospheric_ug_m3") }
    val PM1: MetricSpec by lazy { named("pm1_atmospheric_ug_m3") }

    /** Optional Plantower batch equation, gated separately and never used as the primary PM2.5 reading. */
    val PM25_BATCH_ESTIMATE: MetricSpec by lazy { named(BATCH_ESTIMATE_COLUMN) }
    val PARTICLES_GT03: MetricSpec by lazy { named("particles_gt03_per_01l") }
    val PARTICLES_GT25: MetricSpec by lazy { named("particles_gt25_per_01l") }
    val BOARD_TEMP: MetricSpec by lazy { named("imu_temperature_c") }

    /** Null on CoreS3 (SHT20 address collision), written by the Waveshare PMS5003T. A null RH leaves PM2.5 raw. */
    val AMBIENT_TEMP: MetricSpec by lazy { named("ambient_temperature_c") }
    val HUMIDITY: MetricSpec by lazy { named(AirDerivedMetrics.RELATIVE_HUMIDITY) }
    val BATTERY: MetricSpec by lazy { named("battery_percent") }

    private fun named(id: String): MetricSpec = requireNotNull(Metrics.byId(id)) { "no bundled template has $id" }
}

/** The PM sensor state, null when the sample has no reading of [AirMetrics.PMS_STATUS]. */
val LiveReading.pmsStatus: PmsStatus? get() = PmsStatus.fromCode(code(AirMetrics.PMS_STATUS))

/** True when the PM sensor reports a valid reading now. */
val LiveReading.pmValid: Boolean get() = pmsStatus == PmsStatus.VALID
