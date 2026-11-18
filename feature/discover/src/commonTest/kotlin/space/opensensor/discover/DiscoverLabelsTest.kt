package space.opensensor.discover

import space.opensensor.designsystem.pm25LabelRes
import space.opensensor.family.air.AirFamily
import space.opensensor.family.air.AirProperty
import space.opensensor.family.air.Pm25Basis
import space.opensensor.publicfeed.DiscoverCollection
import space.opensensor.publicfeed.FeedProperty
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * #92 and #145. The public feed has no humidity, so its PM2.5 is always labelled raw. A property the
 * Collection ties to a registry entry takes the registry's words, and only a property the registry
 * does not list keeps the feed label.
 */
class DiscoverLabelsTest {
    init {
        AirFamily.install()
    }

    private fun described(id: String, label: String, category: String = "Air") =
        DiscoverCollection.BUNDLED.describe(listOf(FeedProperty(id, label, "u", category))).single()

    private val pm25 = described("pm25", "PM2.5")
    private val unknown = FeedProperty("radon", "Radon", "Bq/m³", "Air")

    private fun label(property: FeedProperty, language: String = "en") = shownLabel(property, language) { it.key }

    @Test
    fun theFeedPm25IsLabelledRaw() {
        assertEquals(pm25LabelRes(Pm25Basis.RAW), shownLabelRes(pm25))
    }

    @Test
    fun theBundledCollectionTiesEachKnownFeedIdToItsProperty() {
        assertEquals(AirProperty.PM10, described("pm10", "PM10").property)
        assertEquals(AirProperty.PARTICLE_COUNTS.first(), described("um003", "um003").property)
        assertEquals(AirProperty.RELATIVE_HUMIDITY, described("humidity", "Humidity").property)
        assertNull(described("pressure", "Pressure").property)
        assertNull(described("no2", "NO2").property)
    }

    @Test
    fun aRegistryPropertyTakesTheShortLabelInItsLanguage() {
        assertEquals("PM10", label(described("pm10", "Feed pm10")))
        assertEquals("Temperature", label(described("temperature", "Feed temperature", "Weather")))
        assertEquals("CO₂", label(described("co2", "Feed co2")))
        assertEquals(">0.3 µm", label(described("um003", "Feed um003")))
    }

    @Test
    fun withoutAShortLabelInTheLanguageTheRegistryLabelShows() {
        assertEquals("درجة حرارة الهواء", label(described("temperature", "Feed temperature", "Weather"), "ar"))
        assertEquals("الرطوبة النسبية", label(described("humidity", "Feed humidity", "Weather"), "ar"))
    }

    @Test
    fun pressureAndNo2KeepTheAppString() {
        assertEquals("discover_property_pressure", label(described("pressure", "Feed pressure", "Weather")))
        assertEquals("discover_property_no2", label(described("no2", "Feed no2")))
    }

    @Test
    fun aCollectionTitleShowsForAPropertyTheRegistryDoesNotList() {
        // The Collection's column title arrives as the property label.
        val soil = FeedProperty("soil_moisture", "Soil moisture", "m³/m³", "Soil")
        assertNull(shownLabelRes(soil))
        assertEquals("Soil moisture", shownLabel(soil, "en") { error("an unknown id resolves no string") })
        val unlisted = FeedProperty("lux", "Light", "lx", "Weather", property = "urn:example:property:not_in_registry")
        assertEquals("Light", shownLabel(unlisted, "en") { error("an unlisted property resolves no string") })
    }

    @Test
    fun anUnknownIdKeepsTheFeedLabel() {
        assertNull(shownLabelRes(unknown))
        assertEquals("Radon", shownLabel(unknown, "ar") { error("an unknown id resolves no string") })
        assertEquals("Peat", shownCategory("Peat") { error("an unknown category resolves no string") })
    }

    @Test
    fun aKnownCategoryUsesTheAppString() {
        assertEquals("discover_category_weather", shownCategory("Weather") { it.key })
    }
}
