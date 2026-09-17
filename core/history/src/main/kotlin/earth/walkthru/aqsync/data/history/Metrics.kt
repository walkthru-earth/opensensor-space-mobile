package earth.walkthru.aqsync.data.history

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

/** Exclusive upper bound of a display band and the mood it maps to. Not an AQI. */
data class Band(val upTo: Float, val mood: AirMood)

data class MetricSpec(
    /** Stable id, used as the tier key and the metric picker key on both platforms. */
    val id: String,
    val column: String,
    val label: String,
    val unit: String,
    val kind: MetricKind,
    /** Validity gate, or null when every non-null value counts. */
    val gate: Gate?,
    /** Display bands (colour + mood) or null for metrics shown in a neutral colour. */
    val bands: List<Band>?,
    /** Shown in the History metric picker. */
    val chartable: Boolean = true,
    /** Keep min/max in the tiers (an envelope is drawn for it). */
    val envelope: Boolean = false,
    /** Axis floor so a normal range is never clipped away (µg/m³ 60 keeps the "hazy" band visible). */
    val axisFloor: Float = 10f,
    /**
     * Seconds after power-on during which readings count but are flagged as *settling*. The firmware
     * already nulls the first 30 s (`pms_status = 1`); on this archive the next ~3 min still drift by
     * ~2.5 µg/m³ mean-abs against minutes 5–10 (14 boots, 2026-09-17), so 180 s for the PMS. Not a gate.
     */
    val settleSeconds: Long = 0,
) {
    val banded: Boolean get() = bands != null

    fun moodOf(value: Double): AirMood? = bands?.first { value < it.upTo }?.mood

    /** Rows whose gate fails are absent readings, not zeros. */
    fun accepts(gateValue: Long?): Boolean = gate == null || gateValue == gate.value
}

object Metrics {
    /** The PMS gate: `pms_status == 4`. Shared by every particle metric. */
    val PMS_VALID = Gate("pms_status", 4)

    /** WHO 2021 24-h guideline is the first edge (15). The same edges the chart shades and the capybara feels. */
    val PM25_BANDS: List<Band> = AirMood.entries.map { Band(it.upTo, it) }

    /** Measured on this archive, see [MetricSpec.settleSeconds]. */
    const val PMS_SETTLE_S = 180L

    val PM25 = MetricSpec("pm25", "pm25_atmospheric_ug_m3", "PM2.5", "µg/m³", MetricKind.PARTICLE_MASS, PMS_VALID, PM25_BANDS, envelope = true, axisFloor = 60f, settleSeconds = PMS_SETTLE_S)
    val PM10 = MetricSpec("pm10", "pm10_atmospheric_ug_m3", "PM10", "µg/m³", MetricKind.PARTICLE_MASS, PMS_VALID, null, envelope = true, axisFloor = 60f, settleSeconds = PMS_SETTLE_S)
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
    val all: List<MetricSpec> = listOf(PM25, PM10, PM1, PARTICLES_GT03, PARTICLES_GT25, BOARD_TEMP, AMBIENT_TEMP, HUMIDITY, BATTERY, VBUS)
    val chartable: List<MetricSpec> get() = all.filter { it.chartable }

    fun byId(id: String): MetricSpec? = all.firstOrNull { it.id == id }

    /** The one metric the friendly layer speaks about. */
    val primary: MetricSpec get() = PM25
}
