package space.opensensor.discover

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The bundled place search reads GeoNames under CC BY 4.0, so both maps credit it in their map
 * info, in English and Arabic. Discover here, the area picker of `:ui:map` beside it.
 */
class MapCreditTest {
    private fun catalogue(path: String): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path))
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).map { nodes.item(it) as Element }.associate { it.getAttribute("name") to it.textContent }
    }

    private fun assertCredit(text: String?, where: String) {
        assertTrue(text != null && "GeoNames" in text && "CC BY 4.0" in text, "GeoNames credit in $where")
    }

    @Test
    fun discoverMapInfoCreditsGeoNames() {
        val key = DiscoverMapInfo.map { it.key }.single { it == "discover_map_info3" }
        for (folder in listOf("values", "values-ar")) {
            assertCredit(catalogue("src/commonMain/composeResources/$folder/strings.xml")[key], "Discover $folder")
        }
    }

    @Test
    fun pickerMapInfoCreditsGeoNames() {
        // pickerMapInfo always lists map_info_geonames, checked by MapStylesTest in :ui:map.
        for (folder in listOf("values", "values-ar")) {
            assertCredit(catalogue("../../ui/map/src/commonMain/composeResources/$folder/strings.xml")["map_info_geonames"], "picker $folder")
        }
    }
}
