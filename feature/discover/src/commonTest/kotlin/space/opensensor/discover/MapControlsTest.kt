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
    }

    @Test
    fun chartMarksReachUiContrastInLightAndDark() {
        for (scheme in listOf(BrandLightScheme, BrandDarkScheme)) {
            val c = chartColors(scheme)
            for (bg in listOf(scheme.surface, scheme.surfaceContainerLow)) {
                for ((name, mark) in listOf("bar" to c.bar, "track" to c.track, "band" to c.band, "edge" to c.edge)) {
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
    fun ghanaCitiesAreFoundByName() {
        for (city in listOf("Kumasi", "Tema", "Tamale", "Takoradi", "Cape Coast")) {
            val hit = Places.search(city).firstOrNull()
            assertEquals(city, hit?.name, "search for $city")
            assertEquals("Ghana", hit?.country)
        }
        assertEquals("Kumasi", Places.search("كوماسي").firstOrNull()?.name)
    }
}
