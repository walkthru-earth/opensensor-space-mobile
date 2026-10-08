package earth.walkthru.aqsync.ui.components

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
}
