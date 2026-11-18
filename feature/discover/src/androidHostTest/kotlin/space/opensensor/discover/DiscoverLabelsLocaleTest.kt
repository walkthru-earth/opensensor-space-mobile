package space.opensensor.discover

import org.w3c.dom.Element
import space.opensensor.family.air.AirFamily
import space.opensensor.publicfeed.DiscoverCollection
import space.opensensor.publicfeed.FeedProperty
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * #145. Discover names the feed properties it knows in the app's language, from the registry or
 * from the English and Arabic catalogues read here. Only an unknown id shows the feed manifest's
 * English label.
 */
class DiscoverLabelsLocaleTest {
    init {
        AirFamily.install()
    }

    private fun catalogue(path: String): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path))
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).map { nodes.item(it) as Element }.associate { it.getAttribute("name") to it.textContent }
    }

    /** Discover's own strings plus the design system's, where PM2.5 raw lives. */
    private fun strings(folder: String) =
        catalogue("src/commonMain/composeResources/$folder/strings.xml") + catalogue("../../ui/designsystem/src/commonMain/composeResources/$folder/strings.xml")

    private val en = strings("values")
    private val ar = strings("values-ar")

    private fun label(property: FeedProperty, language: Map<String, String>) =
        shownLabel(property, if (language === ar) "ar" else "en") { language.getValue(it.key) }

    private fun described(p: FeedProperty) = DiscoverCollection.BUNDLED.describe(listOf(p)).single()

    private val temperature = described(FeedProperty("temperature", "Temperature", "°C", "Weather"))
    private val humidity = described(FeedProperty("humidity", "Humidity", "%", "Weather"))
    private val pressure = described(FeedProperty("pressure", "Pressure", "hPa", "Weather"))
    private val pm25 = described(FeedProperty("pm25", "PM2.5", "µg/m³", "Air"))
    private val unknown = FeedProperty("radon", "Radon", "Bq/m³", "Air")

    @Test
    fun aKnownIdIsNamedInEnglish() {
        assertEquals("Temperature", label(temperature, en))
        assertEquals("Humidity", label(humidity, en))
        assertEquals("PM2.5 raw", label(pm25, en))
        assertEquals("Pressure", label(pressure, en))
        assertEquals("Weather", shownCategory("Weather") { en.getValue(it.key) })
    }

    @Test
    fun aKnownIdIsNamedInArabic() {
        assertEquals("درجة حرارة الهواء", label(temperature, ar))
        assertEquals("الرطوبة النسبية", label(humidity, ar))
        assertEquals("PM2.5 خام", label(pm25, ar))
        assertEquals("الضغط الجوي", label(pressure, ar))
        assertEquals("الطقس", shownCategory("Weather") { ar.getValue(it.key) })
        assertEquals("الهواء", shownCategory("Air") { ar.getValue(it.key) })
    }

    @Test
    fun theArabicSampleTagIsTheTextTheFontScaleTestRenders() {
        // SampleTagFontScaleTest renders this text on the iOS simulator, which cannot pick the catalogue's locale.
        assertEquals("بيانات تجريبية", ar.getValue("discover_sample_data"))
    }

    @Test
    fun anUnknownIdFallsBackToTheFeedLabelInEveryLanguage() {
        assertEquals("Radon", label(unknown, en))
        assertEquals("Radon", label(unknown, ar))
        assertEquals("Peat", shownCategory("Peat") { ar.getValue(it.key) })
    }
}
