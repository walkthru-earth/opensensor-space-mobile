package earth.walkthru.aqsync.ui.components

import earth.walkthru.aqsync.data.history.NowReading
import earth.walkthru.aqsync.data.protocol.LiveSample
import earth.walkthru.aqsync.data.protocol.PmsStatus
import earth.walkthru.opensensor.discover.DiscoverStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Copy rules for every user-facing string (docs/screens.md, Copy and the info affordance). No em
 * or en dashes, no semicolons and no colons in sentences. Format placeholders (`%1$s`) and
 * clock-like numbers are not sentence colons.
 */
class CopyRulesTest {
    private fun strings(): Map<String, String> {
        val file = listOf(File("src/main/res/values/strings.xml"), File("app/src/main/res/values/strings.xml")).first { it.isFile }
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val out = LinkedHashMap<String, String>()
        val strings = doc.getElementsByTagName("string")
        for (i in 0 until strings.length) {
            val e = strings.item(i) as Element
            out[e.getAttribute("name")] = e.textContent
        }
        val plurals = doc.getElementsByTagName("plurals")
        for (i in 0 until plurals.length) {
            val p = plurals.item(i) as Element
            val items = p.getElementsByTagName("item")
            for (j in 0 until items.length) {
                val item = items.item(j) as Element
                out[p.getAttribute("name") + "/" + item.getAttribute("quantity")] = item.textContent
            }
        }
        return out
    }

    @Test
    fun `no dashes semicolons or sentence colons`() {
        val broken = strings().filter { (_, text) ->
            val sentence = text.replace(Regex("%\\d+\\$[a-z]"), "X").replace(Regex("\\d:\\d"), "0")
            '—' in sentence || '–' in sentence || ';' in sentence || ':' in sentence
        }
        assertTrue("Copy rule violations: $broken", broken.isEmpty())
    }

    /** Copies that iOS reads from shared Kotlin must say what Android says, word for word. */
    @Test
    fun `shared copies match the Android resources`() {
        val s = strings()
        val keys = listOf("who_step_guideline", "who_step_it4", "who_step_it3", "who_step_it2", "who_step_it1", "who_step_above_it1")
        assertEquals(keys.map { s.getValue(it) }, DiscoverStrings.English.whoSteps)
        val warming = NowReading.of(LiveSample(pms = PmsStatus.WARMING.code)) as NowReading.SensorNotReady
        assertEquals(s.getValue("pms_warming_hint"), warming.hint)
    }
}
