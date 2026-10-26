package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.data.protocol.DeviceInfo
import earth.walkthru.aqsync.data.protocol.LiveReading
import earth.walkthru.aqsync.data.protocol.LiveSample
import space.opensensor.model.BundledCatalog

/** INFO and LIVE readings over the bundled templates, for tests. */
internal object LiveFixtures {
    val waveshareDict = BundledCatalog.templates.first { it.schema.startsWith("waveshare-") }.dict
    val cores3Dict = BundledCatalog.templates.first { it.schema.startsWith("cores3-") }.dict
    val waveshare = DeviceInfo(proto = 3, schema = "waveshare-sim7670g-telemetry-v5", dict = waveshareDict)
    val cores3 = DeviceInfo(proto = 1, schema = "cores3-telemetry-v5", dict = cores3Dict)

    fun waveshare(json: String): LiveReading? = DeviceCapabilities.from(waveshare).reading(LiveSample.parse(json))

    fun cores3(json: String): LiveReading? = DeviceCapabilities.from(cores3).reading(LiveSample.parse(json))

    fun waveshare(values: Map<String, Double?>): LiveReading? = DeviceCapabilities.from(waveshare).reading(LiveSample(values = values))
}
