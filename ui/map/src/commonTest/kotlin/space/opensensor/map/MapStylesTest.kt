package space.opensensor.map

import androidx.compose.ui.graphics.Color
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Settings switch for public data keeps every map, the picker included, off the network. */
class MapStylesTest {
    // The loaders here never suspend, so the call finishes before this returns.
    private fun <T> now(block: suspend () -> T): T {
        var out: Result<T>? = null
        block.startCoroutine(Continuation(EmptyCoroutineContext) { out = it })
        return out!!.getOrThrow()
    }

    private val paper = MapStyles.paper(Color.White)

    @Test
    fun offlineNeverAsksForTheBasemap() {
        var asked = 0
        val style = now { MapStyles.choose(false, { paper }) { asked++; "basemap" } }
        assertEquals(paper, style)
        assertEquals(0, asked, "no basemap style read with the switch off")
    }

    @Test
    fun thePaperStyleHasNoSourceOrUrl() {
        assertTrue("\"sources\":{}" in paper)
        assertFalse("://" in paper)
        assertFalse("pmtiles" in paper)
    }

    @Test
    fun onlineFallsBackToPaperWhenTheBasemapFails() {
        assertEquals("basemap", now { MapStyles.choose(true, { paper }) { "basemap" } })
        assertEquals(paper, now { MapStyles.choose(true, { paper }) { error("no basemap") } })
    }

    @Test
    fun thePickerAlwaysCreditsGeoNames() {
        val offline = pickerMapInfo(networkAllowed = false).map { it.key }
        val online = pickerMapInfo(networkAllowed = true).map { it.key }
        assertTrue("map_info_geonames" in offline)
        assertTrue("map_info_geonames" in online)
        assertTrue("map_info_osm" in online && "map_info_osm" !in offline, "the basemap credit only with the basemap")
    }
}
