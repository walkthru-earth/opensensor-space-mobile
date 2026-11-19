package space.opensensor.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import space.opensensor.data.history.DeviceCapabilities
import space.opensensor.data.protocol.DeviceInfo
import space.opensensor.data.protocol.LiveSample
import space.opensensor.family.air.AirFamily
import space.opensensor.family.air.NowReading
import space.opensensor.family.air.PmsStatus
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Android and shared copy say the same words where both name one thing (docs/screens.md, Copy and
 * the info affordance). The copy rules themselves (no dashes, semicolons or sentence colons)
 * are checked for every catalogue in the repository by `:ui:strings` CopyLintTest, which runs
 * without this module.
 */
class CopyRulesTest {
    init {
        AirFamily.install()
    }

    /** The repository root, whether Gradle runs this from `app/` or from the root. */
    private val root: File = listOf(File(".."), File(".")).first { File(it, "settings.gradle.kts").isFile }

    /**
     * The Compose resource catalogues shared with iOS (`feature/<name>` and `ui/<name>`
     * `src/commonMain/composeResources`), by module name.
     */
    private fun sharedStrings(folder: String = "values"): Map<String, Map<String, String>> =
        listOf("feature", "ui").flatMap { File(root, it).listFiles().orEmpty().toList() }.sortedBy { it.name }.mapNotNull { module ->
            val file = File(module, "src/commonMain/composeResources/$folder/strings.xml")
            if (file.isFile) module.name to parse(file) else null
        }.toMap()

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

    @Test
    fun `Today owns the warming hint`() {
        // Today (`:feature:today`) owns the warming hint on both apps.
        val waveshare = DeviceInfo(dict = "a8605aafb6f76b794e9e7fb32927be6d203afeeb14527206c0e49c1eb0436809")
        val sample = LiveSample.parse("""{"pms":${PmsStatus.WARMING.code}}""")
        val warming = NowReading.of(DeviceCapabilities.from(waveshare).reading(sample)) as NowReading.SensorNotReady
        assertEquals(sharedStrings().getValue("today").getValue("pms_warming_hint"), warming.hint)
    }
}
