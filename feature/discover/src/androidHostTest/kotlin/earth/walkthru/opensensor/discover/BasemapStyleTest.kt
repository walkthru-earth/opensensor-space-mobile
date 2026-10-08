package earth.walkthru.opensensor.discover

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The "-latin" styles drawn on iOS, where the map renderer cannot shape right-to-left text. */
class BasemapStyleTest {
    private fun style(name: String) = File("src/commonMain/composeResources/files/basemap/$name.json").readText()

    @Test
    fun latinVariantsNeverDrawComplexScriptNames() {
        for (flavour in listOf("light", "dark")) {
            val latin = style("$flavour-latin")
            for (key in listOf("pgf:name", "\"name2\"", "\"name3\"", "is-supported-script")) {
                assertFalse(key in latin, "$flavour-latin still reads $key")
            }
            assertTrue("[\"case\",[\"has\",\"script\"],\"\",[\"get\",\"name\"]]" in latin, "$flavour-latin lost the Latin fallback")
        }
    }

    @Test
    fun latinVariantsKeepEveryLayer() {
        for (flavour in listOf("light", "dark")) {
            val full = Regex("\"id\":\"").findAll(style(flavour)).count()
            assertEquals(full, Regex("\"id\":\"").findAll(style("$flavour-latin")).count())
        }
    }
}
