package space.opensensor.stac

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CanonicalJsonTest {
    private fun j(text: String) = Json.parseToJsonElement(text)

    @Test
    fun keyOrderAndNumberSpellingDoNotMatter() {
        assertNull(CanonicalJson.difference(j("""{"n":10,"s":"x","g":[1.0,2.0]}"""), j("""{"s":"x","n":10.0,"g":[1,2]}""")))
    }

    @Test
    fun typesLengthsAndKeysMatter() {
        val a = j("""{"n":10,"s":"x","g":[1.0,2.0]}""")
        assertEquals("$.n", CanonicalJson.difference(a, j("""{"n":"10","s":"x","g":[1,2]}""")))
        assertEquals("$.g", CanonicalJson.difference(a, j("""{"n":10,"s":"x","g":[1]}""")))
        assertEquals("$", CanonicalJson.difference(a, j("""{"n":10,"s":"x"}""")))
        assertEquals("$.s", CanonicalJson.difference(j("""{"s":null}"""), j("""{"s":"null"}""")))
    }

    @Test
    fun onlyCoordinatesHaveATolerance() {
        val item = j("""{"bbox":[1,2],"geometry":{"coordinates":[[1,2]]},"p":{"v":2}}""")
        assertNull(CanonicalJson.difference(item, j("""{"bbox":[1,2.0000000001],"geometry":{"coordinates":[[1.0000000001,2]]},"p":{"v":2}}""")))
        assertEquals("$.bbox[1]", CanonicalJson.difference(item, j("""{"bbox":[1,2.000001],"geometry":{"coordinates":[[1,2]]},"p":{"v":2}}""")))
        assertEquals("$.p.v", CanonicalJson.difference(item, j("""{"bbox":[1,2],"geometry":{"coordinates":[[1,2]]},"p":{"v":2.0000000001}}""")))
        val collection = j("""{"extent":{"spatial":{"bbox":[[1,2,3,4]]},"temporal":{"interval":[[1]]}}}""")
        assertNull(CanonicalJson.difference(collection, j("""{"extent":{"spatial":{"bbox":[[1,2,3,4.0000000001]]},"temporal":{"interval":[[1]]}}}""")))
        assertEquals(
            "$.extent.temporal.interval[0][0]",
            CanonicalJson.difference(collection, j("""{"extent":{"spatial":{"bbox":[[1,2,3,4]]},"temporal":{"interval":[[1.0000000001]]}}}""")),
        )
    }
}
