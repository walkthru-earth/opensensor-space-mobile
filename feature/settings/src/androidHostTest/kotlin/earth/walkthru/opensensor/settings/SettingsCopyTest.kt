package earth.walkthru.opensensor.settings

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertTrue

/** The Settings catalogue follows the copy rules. No dashes, semicolons or sentence colons. */
class SettingsCopyTest {
    @Test
    fun copyRules() {
        val file = File("src/commonMain/composeResources/values/strings.xml")
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        val values = (0 until nodes.length).map { (nodes.item(it) as Element).textContent }
        assertTrue(values.size > 20)
        val broken = values.filter {
            val sentence = it.replace(Regex("%\\d+\\$[a-z]"), "X")
            '—' in sentence || '–' in sentence || ';' in sentence || ':' in sentence || "\\'" in sentence
        }
        assertTrue(broken.isEmpty(), "copy rule violations $broken")
    }
}
