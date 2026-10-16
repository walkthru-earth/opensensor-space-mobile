package earth.walkthru.aqsync.data.protocol

/**
 * Reviewed interpretation of a board's LIVE keys, bundled so decoding works offline. [keys] is the
 * set the board's field table can send, so a screen shows a tile only when its key belongs to the
 * board. A listed key may still be missing from one sample, which reads as no value.
 * [temperatureKey] is the key [temperature] is read from, which older CoreS3 firmware sent as `t`.
 */
data class LiveProfile(
    val temperature: TemperatureSource,
    val humidity: Boolean,
    val keys: Set<String>,
    val temperatureKey: String = temperature.key,
) {
    /** The temperature this board reports for [live], read from [temperatureKey]. */
    fun temperatureOf(live: LiveSample?): Double? = when (temperatureKey) {
        "imu_t" -> live?.imuT
        "t" -> live?.t
        else -> null
    }
}

enum class TemperatureSource(val label: String, val key: String) {
    /** CoreS3 `imu_t`, the IMU die temperature. Never shown as air temperature. */
    BOARD("Board temperature", "imu_t"),

    /** Waveshare `t`, the PMS5003T ambient air temperature. */
    AMBIENT("Ambient temperature", "t"),
}

/**
 * The profile comes from the `info.dict` digest, which names the field layout. A newer schema string
 * over the same dictionary keeps its profile, so a firmware that only bumps the row schema is not
 * refused. Never infer the meaning of a key from a device name, firmware prefix or a value. The
 * digest identifies the field layout, not the board or a trusted firmware image. An unknown
 * dictionary still syncs verified files, but has no reviewed interpretation of its LIVE keys.
 * Older dictionaries keep a profile because boards in the field still run them.
 */
object LiveProfiles {
    /** CoreS3 v6.12 writes schema `cores3-telemetry-v5` over dictionary `cores3-telemetry-v4`. */
    const val CORES3_DICTIONARY = "22291d3aab63365404394f30c1549fb18bde0699df9b2de863de70bb3f4edf65"

    /** CoreS3 v6.11 and earlier, `cores3-telemetry-v4`, which sends the IMU temperature as `t`. */
    const val CORES3_V4_DICTIONARY = "f35d73981ca65d9de587a4ec7988ccea19e71dce988c69eb4bb99713f2352bb3"

    /** CoreS3 `cores3-telemetry-v2` and `-v3`, also `t` for the IMU temperature. */
    const val CORES3_V3_DICTIONARY = "20f850852413abb1165f3f7ca95b4831a0e90a34ae7c7fcb41c44b108d3513c3"

    /**
     * Waveshare v2.7 writes schema and dictionary `waveshare-sim7670g-telemetry-v5`, adding `bstate`.
     * Its field table holds the LIVE keys, so the digest covers them (aq-parquet #24, 91442d1).
     */
    const val WAVESHARE_V5_DICTIONARY = "67688d0fd2e86053c1c66b961a1446ad3c225ee71823e0fadb771b645c7183ff"

    /** The first v2.7 build, before its LIVE keys joined the field table. Same keys as v5. */
    const val WAVESHARE_V5_FIRST_DICTIONARY = "d0d0129440e04108d8ca1f9b9555f1b45316ee0ccf3af0fcf7c3a88c8c0796b8"

    /** Waveshare v2.6 writes schema and dictionary `waveshare-sim7670g-telemetry-v4`. */
    const val WAVESHARE_DICTIONARY = "0a024b85c632f621c0e1223f1ecafe3a9c4524a1ec3c0d03d910d191aaee2f82"

    /** Older Waveshare dictionaries, `waveshare-sim7670g-telemetry-v3`, `-v2` and `-v1`. */
    const val WAVESHARE_V3_DICTIONARY = "7cd1c69da4ecd728dde2736e4c55063d9707ef53cdba1745b8896efcfd60f5da"
    const val WAVESHARE_V2_DICTIONARY = "715685cf83ac8d6975fde845ce58fd15fa6eaaf3df32644ec15dfc89875d2e9e"
    const val WAVESHARE_V1_DICTIONARY = "e840c946c10378d28831b38f71daf66981d2ea99cf47ccba80fcd2e64f920ffd"

    private val COMMON_KEYS = setOf("seq", "mono", "utc", "pms", "c1", "c25", "c10", "pm1", "pm25", "pm10", "n03", "n05", "n1", "n25")
    private val CORES3_KEYS = COMMON_KEYS + setOf("n5", "n10", "als", "vbus", "bat", "pct", "chg")
    private val WAVESHARE_KEYS = COMMON_KEYS + setOf("t", "rh", "bat", "pct")

    val CORES3 = LiveProfile(TemperatureSource.BOARD, humidity = false, keys = CORES3_KEYS + "imu_t")
    val CORES3_LEGACY = LiveProfile(TemperatureSource.BOARD, humidity = false, keys = CORES3_KEYS + "t", temperatureKey = "t")
    val WAVESHARE = LiveProfile(TemperatureSource.AMBIENT, humidity = true, keys = WAVESHARE_KEYS)
    val WAVESHARE_V5 = LiveProfile(TemperatureSource.AMBIENT, humidity = true, keys = WAVESHARE_KEYS + "bstate")

    /** The Waveshare profiles, whose live view may show the Plantower batch estimate. */
    val WAVESHARE_FAMILY = setOf(WAVESHARE, WAVESHARE_V5)

    fun resolve(info: DeviceInfo?): LiveProfile? = when (info?.dict) {
        CORES3_DICTIONARY -> CORES3
        CORES3_V4_DICTIONARY, CORES3_V3_DICTIONARY -> CORES3_LEGACY
        WAVESHARE_V5_DICTIONARY, WAVESHARE_V5_FIRST_DICTIONARY -> WAVESHARE_V5
        WAVESHARE_DICTIONARY, WAVESHARE_V3_DICTIONARY, WAVESHARE_V2_DICTIONARY, WAVESHARE_V1_DICTIONARY -> WAVESHARE
        else -> null
    }
}
