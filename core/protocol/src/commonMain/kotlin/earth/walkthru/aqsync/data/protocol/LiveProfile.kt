package earth.walkthru.aqsync.data.protocol

import space.opensensor.model.BundledCatalog

/**
 * A board's LIVE keys, read from its bundled board template and app profile, so decoding works
 * offline. [keys] is the set the board's field table can send, so a screen shows a tile only when its
 * key belongs to the board. A listed key may still be missing from one sample, which reads as no value.
 */
data class LiveProfile(
    val temperature: TemperatureSource,
    val humidity: Boolean,
    val keys: Set<String>,
) {
    /** The temperature this board reports for [live], read only from the [temperature] key. */
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
 * The profile comes from the `info.dict` digest, which names the field layout, through the bundled
 * template for that digest ([BundledCatalog]). A newer schema string over the same dictionary keeps its
 * profile. Never infer the meaning of a key from a device name, firmware prefix or a value. The digest
 * identifies the field layout, not the board or a trusted firmware image.
 *
 * Only the current digest of each board is bundled. An unknown or retired dictionary still syncs
 * verified files, but has no LIVE profile.
 */
object LiveProfiles {
    private val COMMON_KEYS = setOf("seq", "mono", "utc", "pms", "c1", "c25", "c10", "pm1", "pm25", "pm10", "n03", "n05", "n1", "n25")

    /**
     * The reviewed CoreS3 LIVE view, which the bundled CoreS3 template and profile must reproduce. `t`
     * and `rh` stay out on purpose because they are always null on CoreS3.
     */
    val CORES3 = LiveProfile(TemperatureSource.BOARD, humidity = false, keys = COMMON_KEYS + setOf("n5", "n10", "als", "vbus", "bat", "pct", "chg", "imu_t"))

    /** The reviewed Waveshare LIVE view, which the bundled Waveshare template and profile must reproduce. */
    val WAVESHARE = LiveProfile(TemperatureSource.AMBIENT, humidity = true, keys = COMMON_KEYS + setOf("t", "rh", "bat", "pct", "bstate"))

    /**
     * The profile of the bundled template for `info.dict`. Null without a dictionary, for a dictionary
     * the app does not bundle, and for a template whose temperature column LIVE does not carry as `t`
     * or `imu_t`.
     */
    fun resolve(info: DeviceInfo?): LiveProfile? {
        val live = info?.dict?.let(BundledCatalog::live) ?: return null
        val temperature = TemperatureSource.entries.firstOrNull { it.key == live.temperature?.liveKey } ?: return null
        return LiveProfile(temperature, humidity = live.humidity?.liveKey == HUMIDITY_KEY, keys = live.keys)
    }

    private const val HUMIDITY_KEY = "rh"
}
