package space.opensensor.data.history

import space.opensensor.data.protocol.DeviceInfo
import space.opensensor.data.protocol.LiveReading
import space.opensensor.data.protocol.LiveSample

/** INFO and LIVE readings over the bundled templates, for tests. */
internal object LiveFixtures {
    val waveshareDict = "a8605aafb6f76b794e9e7fb32927be6d203afeeb14527206c0e49c1eb0436809"
    val cores3Dict = "3b4d8500a19f42c6743b0f7d725276c96ca14a0c249f613048603afbc3cfaff9"
    val waveshare = DeviceInfo(proto = 3, schema = "waveshare-sim7670g-telemetry-v5", dict = waveshareDict)
    val cores3 = DeviceInfo(proto = 1, schema = "cores3-telemetry-v5", dict = cores3Dict)

    fun waveshare(json: String): LiveReading? = DeviceCapabilities.from(waveshare).reading(LiveSample.parse(json))

    fun cores3(json: String): LiveReading? = DeviceCapabilities.from(cores3).reading(LiveSample.parse(json))

    fun waveshare(values: Map<String, Double?>): LiveReading? = DeviceCapabilities.from(waveshare).reading(LiveSample(values = values))
}
