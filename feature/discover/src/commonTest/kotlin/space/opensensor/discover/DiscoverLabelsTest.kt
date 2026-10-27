package space.opensensor.discover

import space.opensensor.designsystem.pm25LabelRes
import space.opensensor.family.air.Pm25Basis
import space.opensensor.publicfeed.FeedProperty
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * #92 and #145. The public feed has no humidity, so its PM2.5 is always labelled raw. Every other
 * id the app knows gets the app's own string, and only an unknown id keeps the feed label.
 */
class DiscoverLabelsTest {
    private val pm25 = FeedProperty("pm25", "PM2.5", "µg/m³", "Air")
    private val pm10 = FeedProperty("pm10", "PM10", "µg/m³", "Air")
    private val unknown = FeedProperty("radon", "Radon", "Bq/m³", "Air")

    /** Every id the fixtures in tools/discover-fixture publish, plus pressure. */
    private val knownIds = listOf("pm25", "pm10", "pm1", "um003", "temperature", "humidity", "pressure", "co2", "no2")

    @Test
    fun theFeedPm25IsLabelledRaw() {
        assertEquals(pm25LabelRes(Pm25Basis.RAW), shownLabelRes(pm25))
    }

    @Test
    fun everyKnownIdHasItsOwnDistinctString() {
        val resources = knownIds.map { id -> assertNotNull(shownLabelRes(FeedProperty(id, "Feed $id", "u", "Air")), id) }
        assertEquals(knownIds.size, resources.map { it.key }.toSet().size)
        assertNotEquals(pm25LabelRes(Pm25Basis.RAW), shownLabelRes(pm10))
    }

    @Test
    fun aCollectionTitleShowsForAnIdTheAppDoesNotKnow() {
        // The Collection's column title arrives as the property label. A known id keeps the app's string.
        val soil = FeedProperty("soil_moisture", "Soil moisture", "m³/m³", "Soil")
        assertNull(shownLabelRes(soil))
        assertEquals("Soil moisture", shownLabel(soil) { error("an unknown id resolves no string") })
        assertNotNull(shownLabelRes(pm10.copy(label = "Particulate matter 10")))
    }

    @Test
    fun anUnknownIdKeepsTheFeedLabel() {
        assertNull(shownLabelRes(unknown))
        assertEquals("Radon", shownLabel(unknown) { error("an unknown id resolves no string") })
        assertEquals("Peat", shownCategory("Peat") { error("an unknown category resolves no string") })
    }

    @Test
    fun aKnownIdNeverShowsTheFeedLabel() {
        val temperature = FeedProperty("temperature", "Temperature", "°C", "Weather")
        assertEquals("discover_property_temperature", shownLabel(temperature) { it.key })
        assertEquals("discover_category_weather", shownCategory("Weather") { it.key })
    }
}
