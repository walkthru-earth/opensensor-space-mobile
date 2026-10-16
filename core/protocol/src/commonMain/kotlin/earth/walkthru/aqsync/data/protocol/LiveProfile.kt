package earth.walkthru.aqsync.data.protocol

/**
 * Reviewed interpretation of a board's LIVE keys, bundled so decoding works offline. [keys] is the
 * set the board's field table can send, so a screen shows a tile only when its key belongs to the
 * board. A listed key may still be missing from one sample, which reads as no value.
 */
data class LiveProfile(val temperature: TemperatureSource, val humidity: Boolean, val keys: Set<String>) {
    /** The temperature this board reports for [live], read from the key [temperature] names. */
    fun temperatureOf(live: LiveSample?): Double? = when (temperature) {
        TemperatureSource.BOARD -> live?.imuT
        TemperatureSource.AMBIENT -> live?.t
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
 */
object LiveProfiles {
    /** CoreS3 v6.12 writes schema `cores3-telemetry-v5` over dictionary `cores3-telemetry-v4`. */
    const val CORES3_DICTIONARY = "22291d3aab63365404394f30c1549fb18bde0699df9b2de863de70bb3f4edf65"

    /** Waveshare v2.6 writes schema and dictionary `waveshare-sim7670g-telemetry-v4`. */
    const val WAVESHARE_DICTIONARY = "0a024b85c632f621c0e1223f1ecafe3a9c4524a1ec3c0d03d910d191aaee2f82"

    private val COMMON_KEYS = setOf("seq", "mono", "utc", "pms", "c1", "c25", "c10", "pm1", "pm25", "pm10", "n03", "n05", "n1", "n25")

    val CORES3 = LiveProfile(TemperatureSource.BOARD, humidity = false, keys = COMMON_KEYS + setOf("n5", "n10", "imu_t", "als", "vbus", "bat", "pct", "chg"))
    val WAVESHARE = LiveProfile(TemperatureSource.AMBIENT, humidity = true, keys = COMMON_KEYS + setOf("t", "rh", "bat", "pct"))

    fun resolve(info: DeviceInfo?): LiveProfile? = when (info?.dict) {
        CORES3_DICTIONARY -> CORES3
        WAVESHARE_DICTIONARY -> WAVESHARE
        else -> null
    }
}
