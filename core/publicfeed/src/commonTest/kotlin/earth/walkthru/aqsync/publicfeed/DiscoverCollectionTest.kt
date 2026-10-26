package earth.walkthru.aqsync.publicfeed

import earth.walkthru.aqsync.airquality.WhoGuidelines
import earth.walkthru.aqsync.airquality.WhoStep
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DiscoverCollectionTest {
    private val root = "https://example.test/discover"

    private fun collection(assets: String, extra: String = "") = """
        {"type":"Collection","id":"discover-cells","updated":"2026-10-09T03:00:00Z",
         "extent":{"temporal":{"interval":[["2026-10-02T00:00:00Z","2026-10-08T23:59:59Z"]]}},
         "assets":{$assets}$extra}
    """.trimIndent()

    @Test
    fun theFixtureGivesItsDayGenerationCellsTitlesAndScales() {
        val c = DiscoverCollection.parse(fixtureBytes(OpenAqFixture.STAC_COLLECTION).decodeToString(), "$root/")
        assertEquals(LocalDate(2026, 10, 8), c.asOf)
        assertEquals(OpenAqFixture.STAC_UPDATED, c.generation)
        assertEquals(mapOf(5 to "$root/cells/h3_res=5/data.parquet"), c.cells)
        assertEquals("Soil moisture", c.title("soil_moisture"))
        assertNull(c.scale("soil_moisture"))
        assertEquals(WhoGuidelines.URL, c.scale("pm25")?.source)
        assertNull(c.title("co2"))
        assertNull(c.scale("co2"))
    }

    @Test
    fun describeTakesTheTitleAndScaleAndKeepsTheRest() {
        val c = DiscoverCollection.parse(fixtureBytes(OpenAqFixture.STAC_COLLECTION).decodeToString(), root)
        val (soil, co2) = c.describe(listOf(FeedProperty("soil_moisture", "soil_moisture", "m³/m³", "Soil"), FeedProperty("co2", "CO2", "ppm", "Air")))
        assertEquals("Soil moisture", soil.label)
        assertNull(soil.scale)
        assertEquals(FeedProperty("co2", "CO2", "ppm", "Air"), co2)
    }

    @Test
    fun hrefsResolveAgainstTheRootAndUnsafeOnesAreSkipped() {
        val c = DiscoverCollection.parse(
            collection(
                """
                "h3_res=4":{"href":"./cells/h3_res=4/data.parquet"},
                "h3_res=5":{"href":"cells/h3_res=5/data.parquet"},
                "h3_res=6":{"href":"https://cdn.example.test/r6.parquet"},
                "h3_res=7":{"href":"../other/r7.parquet"},
                "h3_res=8":{"href":"/abs/r8.parquet"},
                "h3_res=9":{"href":"file:///etc/r9.parquet"},
                "thumbnail":{"href":"thumb.png"},
                "h3_res=x":{"href":"cells/x.parquet"}
                """.trimIndent(),
            ),
            "$root///",
        )
        assertEquals(
            mapOf(
                4 to "$root/cells/h3_res=4/data.parquet",
                5 to "$root/cells/h3_res=5/data.parquet",
                6 to "https://cdn.example.test/r6.parquet",
            ),
            c.cells,
        )
    }

    @Test
    fun noIntervalEndMeansNoDay() {
        val c = DiscoverCollection.parse("""{"type":"Collection","id":"d","extent":{"temporal":{"interval":[["2026-10-02T00:00:00Z",null]]}}}""", root)
        assertNull(c.asOf)
        assertNull(c.generation)
        assertEquals(emptyMap(), c.cells)
    }

    @Test
    fun anythingButACollectionIsRefused() {
        assertFailsWith<IllegalArgumentException> { DiscoverCollection.parse("""{"type":"Feature","id":"d"}""", root) }
        assertFailsWith<IllegalArgumentException> { DiscoverCollection.parse("""{"type":"Collection","id":"d","assets":{"h3_res=5":{}}}""", root) }
        assertFailsWith<IllegalArgumentException> { DiscoverCollection.parse("not json", root) }
    }

    @Test
    fun theBundledScalesMatchTheWhoGuidelinesStepForStep() {
        for ((id, who) in listOf("pm25" to WhoGuidelines.PM25, "pm10" to WhoGuidelines.PM10)) {
            val steps = StepScale.of(id, DiscoverCollection.BUNDLED.scale(id)!!)!!
            assertEquals(who, steps.who, id)
            assertEquals(who.edges, steps.edges, id)
            assertEquals(who.guideline, steps.guideline, id)
            assertEquals(WhoStep.entries.map { it.color }, steps.colors, id)
            for (step in WhoStep.entries) {
                assertEquals(step.darkFill, steps.darkFill(step.ordinal), "$id $step")
                assertEquals(who.rangeOf(step), steps.rangeOf(step.ordinal), "$id $step")
                // Every value in the step lands on the same index.
                val (low, high) = who.rangeOf(step)
                val probe = high ?: (low!! + 1.0)
                assertEquals(who.stepOf(probe)?.ordinal, steps.stepOf(probe), "$id $probe")
            }
        }
        assertNull(DiscoverCollection.BUNDLED.scale("co2"))
        assertNull(DiscoverCollection.BUNDLED.title("pm25"))
    }
}
