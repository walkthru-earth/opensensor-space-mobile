package earth.walkthru.aqsync.publicfeed

/** Feed properties as a feed describes them, PM with the scales the app ships, the rest neutral. */
internal object TestProperties {
    private fun described(p: FeedProperty) = DiscoverCollection.BUNDLED.describe(listOf(p)).single()

    val PM25 = described(FeedProperty("pm25", "PM2.5", "µg/m³", "Air"))
    val PM10 = described(FeedProperty("pm10", "PM10", "µg/m³", "Air"))
    val CO2 = FeedProperty("co2", "CO2", "ppm", "Air")
    val TEMPERATURE = FeedProperty("temperature", "Temperature", "°C", "Weather")
    val HUMIDITY = FeedProperty("humidity", "Humidity", "%", "Weather")

    val PM25_STEPS: StepScale = StepScale.of("pm25", PM25.scale!!)!!
    val PM10_STEPS: StepScale = StepScale.of("pm10", PM10.scale!!)!!
}
