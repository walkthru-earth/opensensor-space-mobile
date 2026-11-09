package space.opensensor.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaceIndexTest {
    private val index = PlaceIndex.parse(
        listOf(
            "São Paulo\tSao Paulo\tساو باولو\tBR\t-23.55\t-46.64\t12000000",
            "Cairo\t\tالقاهرة\tEG\t30.06\t31.25\t9600000",
            "Accra\t\tأكرا\tGH\t5.56\t-0.20\t2000000",
            "New Cairo\t\t\tEG\t30.03\t31.47\t300000",
            "broken line",
            "Nowhere\t\t\tXX\tnorth\t0.00\t1",
            "Cairo\t\t\tUS\t37.01\t-89.18\t2500",
            "Cairo Montenotte\t\t\tIT\t44.40\t8.27\t2000",
            "",
        ).joinToString("\n"),
    )

    @Test
    fun malformedLinesAreSkipped() {
        assertEquals(6, index.places.size)
        assertEquals(Place("Accra", "", "أكرا", "GH", 5.56, -0.2, 2_000_000), index.places[2])
    }

    @Test
    fun exactThenPrefixThenWordEachByPopulation() {
        assertEquals(
            listOf("Cairo/EG", "Cairo/US", "Cairo Montenotte/IT", "New Cairo/EG"),
            index.search("cairo").map { "${it.name}/${it.country}" },
        )
        assertEquals(listOf("Cairo", "Cairo"), index.search("cai", limit = 2).map { it.name })
        assertTrue(index.search("").isEmpty())
        assertTrue(index.search("   ").isEmpty())
        assertTrue(index.search("zzz").isEmpty())
        assertTrue(index.search("cairo", limit = 0).isEmpty())
    }

    @Test
    fun accentsCaseAndArabicFormsAreIgnored() {
        assertEquals("São Paulo", index.search("SAO pau").first().name)
        assertEquals("São Paulo", index.search("são").first().name)
        assertEquals("Accra", index.search("اكرا").first().name)
        assertEquals("Cairo", index.search("القاهره").first().name)
        assertEquals("Cairo", index.search("قاهر").first().name)
        assertEquals("Cairo", index.search("القَاهِرَة").first().name, "diacritics")
    }

    @Test
    fun theLabelFollowsTheInterfaceLanguage() {
        val accra = index.search("accra").first()
        assertEquals("Accra", accra.label(arabic = false))
        assertEquals("أكرا", accra.label(arabic = true))
        assertEquals("New Cairo", index.search("new cairo").first().label(arabic = true), "no Arabic name")
    }

    @Test
    fun foldKeepsWordsAndDropsTheArticle() {
        assertEquals("sao paulo", PlaceIndex.fold("  São-Paulo "))
        assertEquals("دار بيضاء", PlaceIndex.fold("الدار البيضاء"))
        assertEquals("ال", PlaceIndex.fold("ال"))
        assertEquals("cote d ivoire", PlaceIndex.fold("Côte d'Ivoire"))
    }
}
