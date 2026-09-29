package earth.walkthru.aqsync.data.protocol

/** Reviewed interpretation of optional LIVE fields, bundled so decoding works offline. */
data class LiveProfile(val temperature: TemperatureSource, val humidity: Boolean)

enum class TemperatureSource(val label: String) {
    BOARD("Board temperature"), AMBIENT("Ambient temperature")
}

/**
 * The shared contract reuses `t` for different quantities on different boards. Never infer its
 * meaning from a device name, firmware prefix or a value. Unknown schemas/dictionaries can still
 * sync verified files, but have no reviewed interpretation of this ambiguous LIVE field.
 * Dictionary hashes identify field definitions, not the board or a trusted firmware image.
 */
object LiveProfiles {
    const val CORES3_DICTIONARY = "20f850852413abb1165f3f7ca95b4831a0e90a34ae7c7fcb41c44b108d3513c3"
    const val WAVESHARE_DICTIONARY = "e840c946c10378d28831b38f71daf66981d2ea99cf47ccba80fcd2e64f920ffd"

    fun resolve(info: DeviceInfo?): LiveProfile? = when (info?.schema) {
        "cores3-telemetry-v2", "cores3-telemetry-v3" ->
            if (info.dict == CORES3_DICTIONARY) LiveProfile(TemperatureSource.BOARD, humidity = false) else null
        "waveshare-sim7670g-telemetry-v1" ->
            if (info.dict == WAVESHARE_DICTIONARY) LiveProfile(TemperatureSource.AMBIENT, humidity = true) else null
        else -> null
    }
}
