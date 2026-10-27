package space.opensensor.discover

import space.opensensor.designsystem.BrandDarkScheme
import space.opensensor.designsystem.BrandLightScheme
import space.opensensor.designsystem.UI_COMPONENT_CONTRAST
import space.opensensor.designsystem.contrastRatio
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MapControlsTest {
    @Test
    fun zoomStepsStayInsideTheBasemapRange() {
        assertEquals(6.0, steppedZoom(5.0, 1))
        assertEquals(4.0, steppedZoom(5.0, -1))
        assertEquals(MAX_ZOOM, steppedZoom(MAX_ZOOM, 1))
        assertEquals(MIN_ZOOM, steppedZoom(MIN_ZOOM, -1))
        // A camera outside the range only moves the way pressed, never jumps back the other way.
        assertEquals(18.0, steppedZoom(18.0, 1))
        assertEquals(17.0, steppedZoom(18.0, -1))
        assertEquals(0.5, steppedZoom(0.5, -1))
        assertEquals(1.5, steppedZoom(0.5, 1))
        assertEquals(MAX_ZOOM, steppedZoom(15.5, 1))
    }

    @Test
    fun chartMarksReachUiContrastInLightAndDark() {
        for (scheme in listOf(BrandLightScheme, BrandDarkScheme)) {
            val c = chartColors(scheme)
            for (bg in listOf(scheme.surface, scheme.surfaceContainerLow)) {
                for ((name, mark) in listOf("bar" to c.bar, "track" to c.track, "band" to c.band, "edge" to c.edge)) {
                    // contrastRatio assumes opaque colours, so a translucent mark would pass unseen.
                    assertEquals(1f, mark.alpha, "$name is translucent")
                    val ratio = contrastRatio(mark, bg)
                    assertTrue(ratio >= UI_COMPONENT_CONTRAST, "$name contrast $ratio < $UI_COMPONENT_CONTRAST")
                }
            }
            // The band sits on the track, so it must stand apart from it too.
            val apart = contrastRatio(c.band, c.track)
            assertTrue(apart > 1.0, "band and track are the same colour")
        }
    }

    @Test
    fun edgeLabelsNeverOverlapAndStayInside() {
        // Step edges as a 212 dp axis places them, the review case, and labels at both ends.
        for (fractions in listOf(listOf(0f, 1f / 6, 2f / 6, 3f / 6, 4f / 6, 5f / 6, 1f), listOf(0f, 0.1f, 1f), listOf(0.2f, 0.25f))) {
            val width = 212
            val slot = labelSlot(fractions, width)
            // Every label as wide as it may be, the worst case at a large font.
            val widths = fractions.map { slot }
            val xs = labelPositions(fractions, widths, width)
            val spans = fractions.indices.sortedBy { fractions[it] }.map { xs[it] to xs[it] + widths[it] }
            for ((a, b) in spans.zipWithNext()) assertTrue(a.second <= b.first, "$fractions overlap $a $b")
            for ((start, end) in spans) assertTrue(start >= 0 && end <= width, "$fractions outside $start $end")
        }
    }

    @Test
    fun ghanaCitiesAreFoundByName() {
        for (city in listOf("Kumasi", "Tema", "Tamale", "Takoradi", "Cape Coast")) {
            val hit = Places.search(city).firstOrNull()
            assertEquals(city, hit?.name, "search for $city")
            assertEquals("Ghana", hit?.country)
        }
        assertEquals("Kumasi", Places.search("كوماسي").firstOrNull()?.name)
    }
}
