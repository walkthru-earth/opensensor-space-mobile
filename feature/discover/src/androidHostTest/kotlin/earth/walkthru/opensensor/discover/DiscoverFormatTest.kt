package earth.walkthru.opensensor.discover

import earth.walkthru.aqsync.publicfeed.FeedProperty
import earth.walkthru.aqsync.units.UnitFormatter
import earth.walkthru.aqsync.units.UnitSystem
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiscoverFormatTest {
    private fun p(precision: Double?) = FeedProperty("x", "X", "u", "Air", precision)

    @Test
    fun valuesFollowTheDictionaryPrecision() {
        assertEquals("14", formatValue(13.6, p(1.0)))
        assertEquals("21.5", formatValue(21.5, p(0.5)))
        assertEquals("430", formatValue(430.0, p(10.0)))
        assertEquals("-3.0", formatValue(-3.0, p(0.5)))
        assertEquals("0.1", formatValue(0.06, p(null)))
    }

    @Test
    fun imperialConvertsLabelsOnly() {
        val imperial = UnitFormatter(UnitSystem.IMPERIAL)
        val temp = FeedProperty("temperature", "Temperature", "°C", "Weather", 0.5)
        val pm = FeedProperty("pm25", "PM2.5", "µg/m³", "Air", 1.0)
        assertEquals("70.7", formatValue(21.5, temp, imperial))
        assertEquals("°F", unitLabel(temp, imperial))
        assertEquals("21.5", formatValue(21.5, temp))
        assertEquals("°C", unitLabel(temp))
        assertEquals("14", formatValue(13.6, pm, imperial))
        assertEquals("µg/m³", unitLabel(pm, imperial))
    }

    @Test
    fun searchIsLocalAndIgnoresAccents() {
        assertEquals("Cairo", Places.search("cai").first().name)
        assertEquals("São Paulo", Places.search("sao").first().name)
        assertTrue(Places.search("").isEmpty())
        assertTrue(Places.search("zzz").isEmpty())
    }

    @Test
    fun copyFollowsTheCopyRules() {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/commonMain/composeResources/values/strings.xml"))
        val all = listOf("string", "item").flatMap { tag ->
            val nodes = doc.getElementsByTagName(tag)
            (0 until nodes.length).map { (nodes.item(it) as Element).textContent }
        }
        assertTrue(all.size > 50)
        val broken = all.filter {
            val sentence = it.replace(Regex("%\\d+\\$[a-z]"), "X")
            '—' in sentence || '–' in sentence || ';' in sentence || ':' in sentence || "%%" in sentence || "\\'" in sentence
        }
        assertTrue(broken.isEmpty(), "copy rule violations $broken")
    }
}
