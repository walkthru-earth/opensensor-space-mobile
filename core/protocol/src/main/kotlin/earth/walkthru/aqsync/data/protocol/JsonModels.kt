package earth.walkthru.aqsync.data.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * JSON characteristic payloads from docs/ble-sync-protocol.md.
 *
 * Every field is nullable on purpose: "absent keys mean null (invalid/unavailable), never zero".
 * Unknown keys are ignored so a newer firmware does not break an older phone.
 */
val AqJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = false
    explicitNulls = false
}

/** `info` (read). Static for a boot. */
@Serializable
data class DeviceInfo(
    val proto: Int? = null,
    val fw: String? = null,
    val schema: String? = null,
    val cols: Int? = null,
    /** SHA-256 of telemetry_fields.inc, 64 hex. */
    val dict: String? = null,
    /** Station UUID, same as in every Parquet row and Hive path. */
    val station: String? = null,
    /** 12 hex device_id. */
    val dev: String? = null,
    /** 32 hex boot id. */
    val boot: String? = null,
    /** Largest `length` the device honours per READ. */
    @SerialName("max_read") val maxRead: Int? = null,
) {
    companion object {
        fun parse(text: String): DeviceInfo = AqJson.decodeFromString(serializer(), text)
    }
}

/** `status` (read, notify). */
@Serializable
data class DeviceStatus(
    /** Seconds since boot. */
    @SerialName("up_s") val upSeconds: Long? = null,
    /** Rotation interval in seconds: 600 or 900; firmware v6 adds 1800 and 3600 (set over serial only). */
    @SerialName("int_s") val intervalSeconds: Int? = null,
    /** Rows buffered in RAM, not yet in any file. */
    val buf: Int? = null,
    /**
     * Firmware v6: rows already written and synced into the open `.partial` file whose footer is
     * still pending (one row group per ≤ 90-row batch). They are on the card but not listable until
     * the window closes or a `FLUSH`. Absent (null) on v5.
     */
    val open: Int? = null,
    /** Firmware v6: row groups in that open file. Absent (null) on v5. */
    @SerialName("open_rg") val openRowGroups: Int? = null,
    /** files_finalized. */
    val fin: Long? = null,
    /** rows_dropped. */
    val drop: Long? = null,
    /** storage_errors. */
    val err: Long? = null,
    /** sample_deadlines_missed. */
    val miss: Long? = null,
    /** 1 when the storage worker has stopped writing after an error. */
    val fail: Int? = null,
    val codec: String? = null,
    /** 1 when a UTC anchor is set. */
    val utc: Int? = null,
    /** Anchor generation (clock_epoch). */
    val gen: Int? = null,
    /** Firmware v6.2+: anchor source, 0 none, 1 host SET_TIME, 2 restored from RTC. */
    @SerialName("clk") val clockSource: Int? = null,
    /** Firmware v6.2+: RTC state, 0 not read, 1 in use/written, 2 unusable. */
    @SerialName("rtc") val rtcState: Int? = null,
    /** 1 when the card is mounted and the output directory exists. */
    val sd: Int? = null,
    @SerialName("sd_kib") val sdKib: Long? = null,
    @SerialName("sd_used_kib") val sdUsedKib: Long? = null,
    val heap: Long? = null,
    /** Retained `.partial` files seen at the last listing. */
    val part: Int? = null,
) {
    val utcSynced: Boolean get() = utc == 1
    val storageFailed: Boolean get() = fail == 1
    val sdMounted: Boolean get() = sd == 1

    companion object {
        fun parse(text: String): DeviceStatus = AqJson.decodeFromString(serializer(), text)
    }
}

/** `pms_status` code from the dictionary. */
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

/** `live` (read, notify). One snapshot per stored sample; PM keys appear only when `pms == 4`. */
@Serializable
data class LiveSample(
    val seq: Long? = null,
    /** monotonic_us */
    val mono: Long? = null,
    /** event_time_utc_ns; omitted when unsynchronized. */
    val utc: Long? = null,
    /** pms_status code: 0 absent, 1 warming, 2 stale, 3 sensor error, 4 valid. */
    val pms: Int? = null,
    /** pm*_atmospheric_ug_m3 — the values to display. */
    val pm1: Double? = null,
    val pm25: Double? = null,
    val pm10: Double? = null,
    /** pm*_cf1_ug_m3 */
    val c1: Double? = null,
    val c25: Double? = null,
    val c10: Double? = null,
    /** particles_gt*_per_01l */
    val n03: Long? = null,
    val n05: Long? = null,
    val n1: Long? = null,
    val n25: Long? = null,
    val n5: Long? = null,
    val n10: Long? = null,
    /** imu_temperature_c — board temperature, NOT ambient. */
    val t: Double? = null,
    /** battery_mv */
    val bat: Int? = null,
    /** battery_percent */
    val pct: Int? = null,
    /** charging_status */
    val chg: Int? = null,
    /** vbus_mv */
    val vbus: Int? = null,
    /** light_ch0_raw */
    val als: Long? = null,
) {
    val pmsStatus: PmsStatus? get() = PmsStatus.fromCode(pms)
    val pmValid: Boolean get() = pms == PmsStatus.VALID.code
    val hasUtc: Boolean get() = utc != null

    companion object {
        fun parse(text: String): LiveSample = AqJson.decodeFromString(serializer(), text)
    }
}
