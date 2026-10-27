package space.opensensor.family.air

import space.opensensor.data.history.MetricSpec
import space.opensensor.data.history.Metrics
import space.opensensor.data.protocol.LiveReading

/**
 * The air family's named metrics, the columns of the bundled air boards that the air cards and their
 * tests name. Each one resolves from [Metrics.catalog] on every read, so it follows the families the app
 * installed ([Metrics.install]) and a catalog without it never fails to load. Generic code reads
 * [Metrics] and never these. The derived ones exist once [AirFamily] is installed.
 */
object AirMetrics {
    /** The PM sensor state column, `0` absent to `4` valid. */
    const val PMS_STATUS = "pms_status"

    /** The unvalidated Plantower batch estimate column. */
    const val BATCH_ESTIMATE_COLUMN = "pm25_batch_candidate_ug_m3"

    /** PM2.5 with the US EPA humidity correction, from the stored CF=1 PM2.5 and RH, row by row. Nothing stored changes. */
    val PM25_CORRECTED: MetricSpec get() = named(AirFamily.PM25_CORRECTED_ID)

    /** The sensor's own atmospheric PM2.5, as stored. */
    val PM25: MetricSpec get() = named(AirFamily.PM25_ATMOSPHERIC)
    val PM10: MetricSpec get() = named("pm10_atmospheric_ug_m3")
    val PM1: MetricSpec get() = named("pm1_atmospheric_ug_m3")

    /** Optional Plantower batch equation, gated separately and never used as the primary PM2.5 reading. */
    val PM25_BATCH_ESTIMATE: MetricSpec get() = named(BATCH_ESTIMATE_COLUMN)
    val PARTICLES_GT03: MetricSpec get() = named("particles_gt03_per_01l")
    val PARTICLES_GT25: MetricSpec get() = named("particles_gt25_per_01l")
    val BOARD_TEMP: MetricSpec get() = named("imu_temperature_c")

    /** Null on CoreS3 (SHT20 address collision), written by the Waveshare PMS5003T. A null RH leaves PM2.5 raw. */
    val AMBIENT_TEMP: MetricSpec get() = named("ambient_temperature_c")
    val HUMIDITY: MetricSpec get() = named(AirFamily.RELATIVE_HUMIDITY)
    val BATTERY: MetricSpec get() = named("battery_percent")

    private fun named(id: String): MetricSpec = requireNotNull(Metrics.byId(id)) { "no bundled template has $id" }
}

/** The PM sensor state, the `pms_status` code from the dictionary. */
enum class PmsStatus(val code: Int, val label: String) {
    ABSENT(0, "no PM sensor"),
    WARMING(1, "sensor warming up"),
    STALE(2, "sensor stale"),
    ERROR(3, "sensor error"),
    VALID(4, "valid");

    companion object {
        fun fromCode(code: Int?): PmsStatus? = entries.firstOrNull { it.code == code }
    }
}

/** The PM sensor state, null when the sample has no reading of [AirMetrics.PMS_STATUS]. */
val LiveReading.pmsStatus: PmsStatus? get() = PmsStatus.fromCode(code(AirMetrics.PMS_STATUS))

/** True when the PM sensor reports a valid reading now. */
val LiveReading.pmValid: Boolean get() = pmsStatus == PmsStatus.VALID
