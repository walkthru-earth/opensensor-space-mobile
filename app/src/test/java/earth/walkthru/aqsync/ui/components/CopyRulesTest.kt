package earth.walkthru.aqsync.ui.components

import earth.walkthru.aqsync.data.history.NowReading
import earth.walkthru.aqsync.data.protocol.LiveSample
import earth.walkthru.aqsync.data.protocol.PmsStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Android and shared catalogues say the same words where they share a key (docs/screens.md, Copy
 * and the info affordance). The copy rules themselves (no dashes, semicolons or sentence colons)
 * are checked for every catalogue in the repository by `:ui:strings` CopyLintTest, which runs
 * without this module.
 */
class CopyRulesTest {
    /** The repository root, whether Gradle runs this from `app/` or from the root. */
    private val root: File = listOf(File(".."), File(".")).first { File(it, "settings.gradle.kts").isFile }

    private fun strings(): Map<String, String> = parse(File(root, "app/src/main/res/values/strings.xml"))

    /**
     * The Compose resource catalogues shared with iOS (`feature/<name>` and `ui/<name>`
     * `src/commonMain/composeResources`), by module name.
     */
    private fun sharedStrings(): Map<String, Map<String, String>> =
        listOf("feature", "ui").flatMap { File(root, it).listFiles().orEmpty().toList() }.sortedBy { it.name }.mapNotNull { module ->
            val file = File(module, "src/commonMain/composeResources/values/strings.xml")
            if (file.isFile) module.name to parse(file) else null
        }.toMap()

    /** Android escapes an apostrophe with a backslash, Compose resources do not. Compare the text shown. */
    private fun shown(text: String) = text.replace("\\'", "'")

    private fun parse(file: File): Map<String, String> {
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

    /**
     * A few strings live in both catalogues while Android screens outside the shared modules still
     * read them. They must say the same, word for word.
     */
    @Test
    fun `shared copies match the Android resources`() {
        val s = strings()
        val shared = sharedStrings()
        assertTrue("Shared catalogues missing: ${shared.keys}", shared.keys.containsAll(listOf("discover", "settings", "designsystem", "strings")))
        val both = mapOf(
            "discover" to listOf("who_step_guideline", "who_step_it4", "who_step_it3", "who_step_it2", "who_step_it1", "who_step_above_it1"),
            "settings" to listOf("settings_title", "info_privacy"),
        )
        both.forEach { (module, keys) ->
            keys.forEach { key -> assertEquals("$module/$key", shown(s.getValue(key)), shared.getValue(module).getValue(key)) }
        }
        val warming = NowReading.of(LiveSample(pms = PmsStatus.WARMING.code)) as NowReading.SensorNotReady
        assertEquals(s.getValue("pms_warming_hint"), warming.hint)
    }
}
