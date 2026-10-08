package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.airquality.HealthScale
import earth.walkthru.aqsync.airquality.WhoGuidelines
import earth.walkthru.aqsync.airquality.WhoStep

/**
 * The metric registry: everything the app knows about a measurable quantity, as data. A new sensor
 * (CO₂, VOC, humidity, noise…) is a new [MetricSpec] here — the tiers, the daily files, the charts and
 * the friendly layer pick it up without code changes, because none of them name a column.
 *
 * Column names follow the firmware's field dictionary (`telemetry_fields.inc`); a metric whose column
 * is absent from a file simply produces no values for it (v1 files lack the four v2 columns, and the
 * SHT20 ambient columns are null on the current board), so the registry can be ahead of the hardware.
 */
enum class MetricKind { PARTICLE_MASS, PARTICLE_COUNT, TEMPERATURE, HUMIDITY, GAS, POWER, OTHER }

/** A row's value counts only when `column == value` (e.g. `pms_status == 4`: present, warmed up, fresh, no error). */
data class Gate(val column: String, val value: Long)

data class MetricSpec(
    /** Stable id, used as the tier key and the metric picker key on both platforms. */
    val id: String,
    val column: String,
    val label: String,
    val unit: String,
    val kind: MetricKind,
    /** Validity gate, or null when every non-null value counts. */
    val gate: Gate?,
    /**
     * The published health scale (WHO 2021 for PM2.5 and PM10) that colours and names a value, or
     * null for a metric shown in a neutral colour. Its property id and unit must match this metric.
     */
    val healthScale: HealthScale?,
    /** Shown in the History metric picker. */
    val chartable: Boolean = true,
    /** Keep min/max in the tiers (an envelope is drawn for it). */
    val envelope: Boolean = false,
    /** Axis floor so a normal range is never clipped away. A banded axis snaps it up to a WHO edge. */
    val axisFloor: Float = 10f,
    /**
     * Seconds after power-on during which readings count but are flagged as *settling*. The firmware
     * already nulls the first 30 s (`pms_status = 1`); on this archive the next ~3 min still drift by
     * ~2.5 µg/m³ mean-abs against minutes 5–10 (14 boots, 2026-09-17), so 180 s for the PMS. Not a gate.
     */
    val settleSeconds: Long = 0,
) {
    init {
        require(healthScale == null || (healthScale.propertyId == id && healthScale.unit == unit)) {
            "a health scale reads only its own property ($id, $unit)"
        }
    }

    val banded: Boolean get() = healthScale != null

    /** The WHO step of [value], or null for a neutral metric or a non-finite value. */
    fun stepOf(value: Double): WhoStep? = healthScale?.stepOf(value)

    fun moodOf(value: Double): AirMood? = stepOf(value)?.let { AirMood.of(it) }

    /** Rows whose gate fails are absent readings, not zeros. */
    fun accepts(gateValue: Long?): Boolean = gate == null || gateValue == gate.value
}

object Metrics {
    /** The PMS gate: `pms_status == 4`. Shared by every particle metric. */
    val PMS_VALID = Gate("pms_status", 4)

    /** Measured on this archive, see [MetricSpec.settleSeconds]. */
    const val PMS_SETTLE_S = 180L

    val PM25 = MetricSpec("pm25", "pm25_atmospheric_ug_m3", "PM2.5", "µg/m³", MetricKind.PARTICLE_MASS, PMS_VALID, WhoGuidelines.PM25, envelope = true, axisFloor = 37.5f, settleSeconds = PMS_SETTLE_S)

    /** Optional Plantower batch equation, gated separately and never used as the primary PM2.5 reading. */
    val PM25_BATCH_ESTIMATE = MetricSpec("pm25_batch", "pm25_batch_candidate_ug_m3", "PM2.5 batch estimate (unvalidated)", "µg/m³", MetricKind.PARTICLE_MASS, Gate("pm25_batch_candidate_status", 3), null, envelope = true, axisFloor = 60f, settleSeconds = PMS_SETTLE_S)
    val PM10 = MetricSpec("pm10", "pm10_atmospheric_ug_m3", "PM10", "µg/m³", MetricKind.PARTICLE_MASS, PMS_VALID, WhoGuidelines.PM10, envelope = true, axisFloor = 75f, settleSeconds = PMS_SETTLE_S)
    val PM1 = MetricSpec("pm1", "pm1_atmospheric_ug_m3", "PM1", "µg/m³", MetricKind.PARTICLE_MASS, PMS_VALID, null, envelope = true, axisFloor = 60f, settleSeconds = PMS_SETTLE_S)
    val PARTICLES_GT03 = MetricSpec("n03", "particles_gt03_per_01l", "Particles > 0.3 µm", "per 0.1 L", MetricKind.PARTICLE_COUNT, PMS_VALID, null, chartable = false, settleSeconds = PMS_SETTLE_S)
    val PARTICLES_GT25 = MetricSpec("n25", "particles_gt25_per_01l", "Particles > 2.5 µm", "per 0.1 L", MetricKind.PARTICLE_COUNT, PMS_VALID, null, chartable = false, settleSeconds = PMS_SETTLE_S)
    val BOARD_TEMP = MetricSpec("board_c", "imu_temperature_c", "Board temperature", "°C", MetricKind.TEMPERATURE, null, null)

    /** Null on the current board (SHT20 address collision); listed so the day the sensor works, History does too. */
    val AMBIENT_TEMP = MetricSpec("ambient_c", "ambient_temperature_c", "Temperature", "°C", MetricKind.TEMPERATURE, null, null)
    val HUMIDITY = MetricSpec("rh", "relative_humidity_percent", "Humidity", "%", MetricKind.HUMIDITY, null, null, axisFloor = 100f)
    val BATTERY = MetricSpec("battery", "battery_percent", "Battery", "%", MetricKind.POWER, null, null, chartable = false, axisFloor = 100f)
    val VBUS = MetricSpec("vbus", "vbus_mv", "USB voltage", "mV", MetricKind.POWER, null, null, chartable = false)

    /** Every metric the tiers keep. Order = picker order. */
    val all: List<MetricSpec> = listOf(PM25, PM25_BATCH_ESTIMATE, PM10, PM1, PARTICLES_GT03, PARTICLES_GT25, BOARD_TEMP, AMBIENT_TEMP, HUMIDITY, BATTERY, VBUS)
    val chartable: List<MetricSpec> get() = all.filter { it.chartable }

    fun byId(id: String): MetricSpec? = all.firstOrNull { it.id == id }

    /** The one metric the friendly layer speaks about. */
    val primary: MetricSpec get() = PM25
}
