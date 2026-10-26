package earth.walkthru.aqsync.data.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * JSON characteristic payloads from ../aq-parquet/docs/shared/ble-sync-protocol.md.
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
    /**
     * First 16 hex of the SHA-256 of the board template that `GET_DESCRIPTOR` serves. Absent means
     * the firmware cannot serve one.
     */
    val desc: String? = null,
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
    /** Anchor source: 0 none, 1 host SET_TIME, 2 restored RTC, 3 network SNTP. */
    @SerialName("clk") val clockSource: Int? = null,
    /** Current UTC estimate at the STATUS snapshot; absent on older firmware or without UTC. */
    @SerialName("utc_ms") val utcEpochMillis: Long? = null,
    /** Seconds since the latest accepted UTC anchor; absent before anchoring or on older firmware. */
    @SerialName("clk_age_s") val clockAgeSeconds: Long? = null,
    /** Firmware v6.2+: RTC state, 0 not read, 1 in use/written, 2 unusable. */
    @SerialName("rtc") val rtcState: Int? = null,
    /** 1 when the card is mounted and the output directory exists. */
    val sd: Int? = null,
    @SerialName("sd_kib") val sdKib: Long? = null,
    @SerialName("sd_used_kib") val sdUsedKib: Long? = null,
    val heap: Long? = null,
    /** Retained `.partial` files seen at the last listing. */
    val part: Int? = null,
    /** Protocol 3. BLE and LAN sessions closed since boot, refused LAN clients included, saturating at 65535. */
    val dc: Int? = null,
    /** Protocol 3. The latest [DisconnectReason] code, absent while [dc] is 0. */
    val dr: Int? = null,
    /** Card state from walkthru-earth/aq-parquet#5, 0 ready, 1 degraded, 2 full, 3 retrying after an error. */
    @SerialName("sd_state") val sdState: Int? = null,
) {
    val utcSynced: Boolean get() = utc == 1
    val storageFailed: Boolean get() = fail == 1
    val sdMounted: Boolean get() = sd == 1
    val lastDisconnect: DisconnectReason? get() = dr?.let(DisconnectReason::fromCode)
    val sdDegraded: Boolean get() = sdState == SD_DEGRADED
    val sdFull: Boolean get() = sdState == SD_FULL

    companion object {
        const val SD_READY: Int = 0
        const val SD_DEGRADED: Int = 1
        const val SD_FULL: Int = 2
        const val SD_RETRY: Int = 3

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

/**
 * `live` (read, notify). One snapshot per stored sample, read as a map by key name. The board sends
 * every key its field table declares, each as a number or `null`. `null` means no reading now, and an
 * absent key means the board has no such field. A number may print as `1.0` or `1.00` and parses to
 * the same value, the app keeps its own display rounding. When the board's buffer overflows it sends
 * one object with every key `null` ([overflow]), which clears the live values.
 *
 * [seq], [mono] and [utc] are typed header fields. Every other key lands in [values] by its key name,
 * a number as a [Double] and `null` as null. A value that is not a number is skipped. Keys mean
 * nothing on their own, [LiveReading] maps them to template columns and ignores undeclared ones.
 */
data class LiveSample(
    val seq: Long? = null,
    /** monotonic_us */
    val mono: Long? = null,
    /** UTC in nanoseconds (LIVE keeps ns while the Parquet column is in microseconds), null when unsynchronized. */
    val utc: Long? = null,
    /** Every other key by its key name. */
    val values: Map<String, Double?> = emptyMap(),
    /** True when every key of the object was `null`, the board's overflow sample. */
    val overflow: Boolean = false,
) {
    /** The value of the LIVE [key], null when the key is absent or carries no reading now. */
    operator fun get(key: String): Double? = values[key]

    /** True when the sample carries [key], even as `null`. */
    fun has(key: String): Boolean = key in values

    companion object {
        const val SEQ = "seq"
        const val MONO = "mono"
        const val UTC = "utc"

        /** The typed header fields, never read as values. */
        val HEADER: Set<String> = setOf(SEQ, MONO, UTC)

        /** @throws IllegalArgumentException when [text] is not a JSON object. */
        fun parse(text: String): LiveSample {
            val obj = AqJson.parseToJsonElement(text) as? JsonObject ?: throw IllegalArgumentException("LIVE is not a JSON object")
            val values = LinkedHashMap<String, Double?>()
            for ((key, element) in obj) {
                if (key in HEADER) continue
                when {
                    element is JsonNull -> values[key] = null
                    element is JsonPrimitive && !element.isString -> element.doubleOrNull?.let { values[key] = it }
                }
            }
            return LiveSample(
                seq = obj.header(SEQ),
                mono = obj.header(MONO),
                utc = obj.header(UTC),
                values = values,
                overflow = obj.isNotEmpty() && obj.values.all { it is JsonNull },
            )
        }

        private fun JsonObject.header(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.let {
            it.longOrNull ?: it.doubleOrNull?.takeIf { d -> d.isFinite() && d == kotlin.math.floor(d) }?.toLong()
        }
    }
}
