package space.opensensor.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import space.opensensor.data.history.DeviceCapabilities
import space.opensensor.data.history.NowReading
import space.opensensor.data.protocol.DeviceInfo
import space.opensensor.data.protocol.LiveSample
import space.opensensor.data.protocol.PmsStatus
import space.opensensor.model.BundledCatalog
import space.opensensor.publicfeed.DiscoverCollection
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Android and shared copy say the same words where both name one thing (docs/screens.md, Copy and
 * the info affordance). The copy rules themselves (no dashes, semicolons or sentence colons)
 * are checked for every catalogue in the repository by `:ui:strings` CopyLintTest, which runs
 * without this module.
 */
class CopyRulesTest {
    /** The repository root, whether Gradle runs this from `app/` or from the root. */
    private val root: File = listOf(File(".."), File(".")).first { File(it, "settings.gradle.kts").isFile }

    private fun strings(folder: String = "values"): Map<String, String> = parse(File(root, "app/src/main/res/$folder/strings.xml"))

    /**
     * The Compose resource catalogues shared with iOS (`feature/<name>` and `ui/<name>`
     * `src/commonMain/composeResources`), by module name.
     */
    private fun sharedStrings(folder: String = "values"): Map<String, Map<String, String>> =
        listOf("feature", "ui").flatMap { File(root, it).listFiles().orEmpty().toList() }.sortedBy { it.name }.mapNotNull { module ->
            val file = File(module, "src/commonMain/composeResources/$folder/strings.xml")
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
     * Discover names WHO steps from the PM scales the app ships (`DiscoverCollection.BUNDLED`), the
     * things from the Android step strings. Both say the same in English and Arabic.
     */
    @Test
    fun `bundled WHO step names match the Android step strings`() {
        val keys = listOf("who_step_guideline", "who_step_it4", "who_step_it3", "who_step_it2", "who_step_it1", "who_step_above_it1")
        for ((folder, lang) in listOf("values" to "en", "values-ar" to "ar")) {
            val s = strings(folder)
            for (id in listOf("pm25", "pm10")) {
                val scale = checkNotNull(DiscoverCollection.BUNDLED.scale(id)) { "$id has no bundled scale" }
                assertEquals("$folder $id", keys.map { shown(s.getValue(it)) }, scale.steps.map { it.label.get(lang) })
            }
        }
    }

    @Test
    fun `Today owns the warming hint`() {
        // Today (`:feature:today`) owns the warming hint on both apps.
        val waveshare = DeviceInfo(dict = BundledCatalog.templates.first { it.schema.startsWith("waveshare-") }.dict)
        val sample = LiveSample.parse("""{"pms":${PmsStatus.WARMING.code}}""")
        val warming = NowReading.of(DeviceCapabilities.from(waveshare).reading(sample)) as NowReading.SensorNotReady
        assertEquals(sharedStrings().getValue("today").getValue("pms_warming_hint"), warming.hint)
    }
}
