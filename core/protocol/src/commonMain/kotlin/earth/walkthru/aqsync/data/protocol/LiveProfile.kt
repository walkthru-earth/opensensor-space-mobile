package earth.walkthru.aqsync.data.protocol

/**
 * Reviewed interpretation of a board's LIVE keys, bundled so decoding works offline. [keys] is the
 * set the board's field table can send, so a screen shows a tile only when its key belongs to the
 * board. A listed key may still be missing from one sample, which reads as no value.
 * [temperatureKey] is the key [temperature] is read from, which older CoreS3 firmware sent as `t`.
 * Only reviewed CoreS3 profiles read `imu_t`, so their fallback to `t` is the same board temperature
 * under the old key. [reviewed] is false for [LiveProfiles.vocabulary], built from the pinned LIVE
 * keys of an unknown dictionary, which never reads one key as another.
 */
data class LiveProfile(
    val temperature: TemperatureSource,
    val humidity: Boolean,
    val keys: Set<String>,
    val temperatureKey: String = temperature.key,
    val reviewed: Boolean = true,
) {
    /** The temperature this board reports for [live], read from [temperatureKey]. */
    fun temperatureOf(live: LiveSample?): Double? = when (temperatureKey) {
        "imu_t" -> live?.imuT ?: live?.t?.takeIf { reviewed }
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
 * digest identifies the field layout, not the board or a trusted firmware image. Older dictionaries
 * keep a profile because boards in the field still run them.
 *
 * An unknown dictionary still syncs verified files, and its LIVE keys fall back to [vocabulary].
 * That relies on the aq-parquet rule that a LIVE key never changes meaning once released
 * (`docs/shared/ble-sync-protocol.md`, "LIVE key stability").
 */
object LiveProfiles {
    /**
     * CoreS3 v6.13 writes schema and dictionary `cores3-telemetry-v5`. Its field table holds the LIVE
     * keys, so the digest covers them (aq-parquet #31). The keys match the last v6.12 build, and `t`
     * and `rh` stay out of [CORES3] on purpose because they are always null on CoreS3.
     */
    const val CORES3_V5_DICTIONARY = "e5060fb7fedbcabf665b1c8a159a77f5fe9283ac34bd2d89f1bc98b0f027d38b"

    /**
     * CoreS3 v6.12 writes schema `cores3-telemetry-v5` over dictionary `cores3-telemetry-v4`. The digest
     * does not cover the LIVE keys. Two v6.12 builds share it, one sends the IMU temperature as `t` and
     * one as `imu_t`, so [CORES3] falls back to `t`.
     */
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

    /**
     * The reviewed profile of a known dictionary. For an unknown one, the unreviewed [vocabulary]
     * profile of [live], and null when `info` carries no dictionary at all.
     */
    fun resolve(info: DeviceInfo?, live: LiveSample? = null): LiveProfile? = when (info?.dict) {
        null -> null
        CORES3_V5_DICTIONARY, CORES3_DICTIONARY -> CORES3
        CORES3_V4_DICTIONARY, CORES3_V3_DICTIONARY -> CORES3_LEGACY
        WAVESHARE_V5_DICTIONARY, WAVESHARE_V5_FIRST_DICTIONARY -> WAVESHARE_V5
        WAVESHARE_DICTIONARY, WAVESHARE_V3_DICTIONARY, WAVESHARE_V2_DICTIONARY, WAVESHARE_V1_DICTIONARY -> WAVESHARE
        else -> vocabulary(live)
    }

    /**
     * Every LIVE key whose meaning the aq-parquet LIVE table pins on every board
     * (`docs/shared/ble-sync-protocol.md`). Each one is a [LiveSample] field, and [LiveSample] drops
     * any other key while parsing.
     */
    val VOCABULARY = setOf(
        "seq", "mono", "utc", "pms", "pm1", "pm25", "pm10", "c1", "c25", "c10",
        "n03", "n05", "n1", "n25", "n5", "n10", "t", "rh", "imu_t", "bat", "pct", "chg", "vbus", "bstate", "als",
    )

    /**
     * The unreviewed profile of an unknown dictionary, built only from the [VOCABULARY] keys [live]
     * carries. `t` is ambient air temperature and `imu_t` is board temperature on every board, so the
     * source follows the key. Ambient wins when both are sent, and without `t` the profile reads only
     * `imu_t`. Humidity shows only when `rh` is sent. An absent key reads as null, so a key missing
     * from one sample also leaves the profile.
     */
    fun vocabulary(live: LiveSample?): LiveProfile {
        val keys = keysOf(live)
        val ambient = "t" in keys
        return LiveProfile(
            temperature = if (ambient) TemperatureSource.AMBIENT else TemperatureSource.BOARD,
            humidity = "rh" in keys,
            keys = keys,
            reviewed = false,
        )
    }

    /** The [VOCABULARY] keys present in [live]. A null field is an absent key. */
    fun keysOf(live: LiveSample?): Set<String> {
        if (live == null) return emptySet()
        val values = mapOf(
            "seq" to live.seq, "mono" to live.mono, "utc" to live.utc, "pms" to live.pms,
            "pm1" to live.pm1, "pm25" to live.pm25, "pm10" to live.pm10,
            "c1" to live.c1, "c25" to live.c25, "c10" to live.c10,
            "n03" to live.n03, "n05" to live.n05, "n1" to live.n1, "n25" to live.n25, "n5" to live.n5, "n10" to live.n10,
            "t" to live.t, "rh" to live.rh, "imu_t" to live.imuT,
            "bat" to live.bat, "pct" to live.pct, "chg" to live.chg, "vbus" to live.vbus, "bstate" to live.bstate, "als" to live.als,
        )
        return values.filterValues { it != null }.keys
    }
}
