package earth.walkthru.opensensor.shell

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The shell catalogue follows the copy rules. No dashes, semicolons or colons. */
class ShellCopyTest {
    @Test
    fun copyRules() {
        val file = File("src/commonMain/composeResources/values/strings.xml")
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        val values = (0 until nodes.length).map { (nodes.item(it) as Element).textContent }
        assertEquals(listOf("Discover", "My devices", "Settings"), values)
        val broken = values.filter { '—' in it || '–' in it || ';' in it || ':' in it }
        assertTrue(broken.isEmpty(), "copy rule violations $broken")
    }
}
