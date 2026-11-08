package space.opensensor.map

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The real bundled GeoNames file, read from the module as the app reads it from its resources. */
class BundledPlacesTest {
    private val file = File("src/commonMain/composeResources/${PlaceIndex.FILE}")
    private val index by lazy { PlaceIndex.parse(PlaceSearch.gunzip(file.readBytes()).decodeToString()) }

    @Test
    fun theFileStaysSmallAndEveryLineParses() {
        assertTrue(file.length() < 700_000, "bundled places are ${file.length()} bytes")
        val lines = PlaceSearch.gunzip(file.readBytes()).decodeToString().lines().filter { it.isNotEmpty() }
        assertEquals(lines.size, index.places.size)
        assertTrue(index.places.size > 30_000)
        // Two decimals at most, about 1 km, so the file holds no finer point than a town needs.
        val coordinate = Regex("-?\\d{1,3}(\\.\\d{1,2})?")
        assertTrue(lines.all { l -> l.split('\t').let { coordinate.matches(it[4]) && coordinate.matches(it[5]) } })
        assertTrue(index.places.zipWithNext().all { (a, b) -> a.population >= b.population }, "largest first")
    }

    @Test
    fun citiesAreFoundInEnglishAndArabic() {
        assertEquals("Cairo" to "EG", index.search("cai").first().let { it.name to it.country })
        assertEquals("São Paulo", index.search("sao").first().name)
        assertEquals("Accra" to "GH", index.search("accra").first().let { it.name to it.country })
        assertEquals("Accra", index.search("أكرا").first().name)
        assertEquals("Cairo", index.search("القاهرة").first().name)
        assertEquals("Cairo", index.search("قاهر").first().name)
        assertEquals("Riyadh", index.search("الرياض").first().name)
    }

    @Test
    fun ghanaCitiesAreFoundByName() {
        for (city in listOf("Kumasi", "Tema", "Tamale", "Takoradi", "Cape Coast")) {
            val hit = index.search(city).firstOrNull { it.country == "GH" }
            assertEquals(city, hit?.name, "search for $city")
        }
        assertEquals("Kumasi", index.search("كوماسي").firstOrNull()?.name)
    }
}
