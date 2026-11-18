package space.opensensor.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import space.opensensor.data.history.DeviceCapabilities
import space.opensensor.data.protocol.DeviceInfo
import space.opensensor.data.protocol.LiveSample
import space.opensensor.family.air.AirFamily
import space.opensensor.family.air.NowReading
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

    /**
     * Discover names WHO steps from the registry's WHO scales, which the PM columns of the bundled
     * Collection name, so the app keeps no copy of their words. Each step is named in English and Arabic.
     */
    @Test
    fun `Discover names WHO steps from the registry scales`() {
        for (id in listOf("pm25", "pm10")) {
            val scale = checkNotNull(DiscoverCollection.BUNDLED.scale(id)) { "$id has no bundled scale" }
            assertEquals(id, BundledCatalog.scales["who-2021-$id"], scale)
            for (lang in listOf("en", "ar")) {
                assertTrue("$id $lang", scale.steps.all { it.label.byLanguage[lang]?.isNotBlank() == true })
            }
        }
    }

    @Test
    fun `Today owns the sensor state hints`() {
        // Today (`:feature:today`) owns the hints on both apps, keyed by the class name of the state.
        val waveshare = DeviceInfo(dict = "a8605aafb6f76b794e9e7fb32927be6d203afeeb14527206c0e49c1eb0436809")
        val today = sharedStrings().getValue("today")
        for ((code, key) in listOf(0 to "pms_absent_hint", 1 to "pms_warming_hint", 2 to "pms_stale_hint", 3 to "pms_error_hint", 5 to "pms_mismatch_hint", 6 to "pms_sleeping_hint", 9 to "pms_other_hint")) {
            val sample = LiveSample.parse("""{"pms":$code}""")
            val state = NowReading.of(DeviceCapabilities.from(waveshare).reading(sample)) as NowReading.SensorNotReady
            assertEquals("pms $code", today.getValue(key), state.hint)
        }
    }
}
