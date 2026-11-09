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
    fun citiesAreFoundByName() {
        assertEquals("Cairo" to "EG", index.search("cai").first().let { it.name to it.country })
        assertEquals("São Paulo", index.search("sao").first().name)
        assertEquals("Accra" to "GH", index.search("accra").first().let { it.name to it.country })
    }

    @Test
    fun onlyCuratedCitiesHaveArabicNames() {
        // Untagged alternates would label Casablanca with an old district name, أنفا.
        val casablanca = index.search("casablanca").first()
        assertEquals("Casablanca" to "MA", casablanca.name to casablanca.country)
        assertEquals("الدار البيضاء", casablanca.label(arabic = true))
        assertEquals(53, index.places.count { it.ar.isNotEmpty() }, "the curated list of make_places.py")
        val other = index.search("rabat").first()
        assertEquals("Rabat", other.label(arabic = true), "an uncurated city keeps its GeoNames name")
    }

    @Test
    fun curatedCitiesAreFoundInArabic() {
        assertEquals("Casablanca", index.search("الدار البيضاء").first().name)
        assertEquals("Accra", index.search("أكرا").first().name)
        assertEquals("Cairo", index.search("القاهرة").first().name)
        assertEquals("Cairo", index.search("قاهر").first().name)
        assertEquals("Kumasi", index.search("كوماسي").first().name)
        assertEquals("Tema" to "GH", index.search("تيما").first().let { it.name to it.country })
    }

    @Test
    fun ghanaCitiesAreFoundByName() {
        for (city in listOf("Kumasi", "Tema", "Tamale", "Takoradi", "Cape Coast")) {
            val hit = index.search(city).firstOrNull { it.country == "GH" }
            assertEquals(city, hit?.name, "search for $city")
        }
    }
}
