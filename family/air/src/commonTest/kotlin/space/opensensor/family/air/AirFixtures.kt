package space.opensensor.family.air

import space.opensensor.data.history.DeviceCapabilities
import space.opensensor.data.protocol.DeviceInfo
import space.opensensor.data.protocol.LiveReading
import space.opensensor.data.protocol.LiveSample
import space.opensensor.publicfeed.DiscoverCollection
import space.opensensor.publicfeed.FeedProperty

/** INFO and LIVE readings over the bundled air boards, with the air family installed, for tests. */
internal object LiveFixtures {
    init {
        AirFamily.install()
    }

    val waveshareDict = "50a53393e940be182ded0b1119de6ac6d9521e7d28dfd244a24666ccf2a616c7"
    val cores3Dict = "3b4d8500a19f42c6743b0f7d725276c96ca14a0c249f613048603afbc3cfaff9"
    val waveshare = DeviceInfo(proto = 3, schema = "waveshare-sim7670g-telemetry-v5", dict = waveshareDict)
    val cores3 = DeviceInfo(proto = 1, schema = "cores3-telemetry-v5", dict = cores3Dict)

    fun waveshare(json: String): LiveReading? = DeviceCapabilities.from(waveshare).reading(LiveSample.parse(json))

    fun cores3(json: String): LiveReading? = DeviceCapabilities.from(cores3).reading(LiveSample.parse(json))
}

/** Feed properties as a feed describes them, PM with the air family's scales, the rest neutral. */
internal object TestProperties {
    init {
        AirFamily.install()
    }

    private fun described(p: FeedProperty) = DiscoverCollection.BUNDLED.describe(listOf(p)).single()

    val PM25 = described(FeedProperty("pm25", "PM2.5", "µg/m³", "Air"))
    val CO2 = FeedProperty("co2", "CO2", "ppm", "Air")
}
