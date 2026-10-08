package earth.walkthru.opensensor.discover

import earth.walkthru.aqsync.publicfeed.FeedProperty
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiscoverFormatTest {
    private fun p(precision: Double?) = FeedProperty("x", "X", "u", "Air", precision)

    @Test
    fun valuesFollowTheDictionaryPrecision() {
        assertEquals("14", formatValue(13.6, p(1.0)))
        assertEquals("21.5", formatValue(21.5, p(0.5)))
        assertEquals("430", formatValue(430.0, p(10.0)))
        assertEquals("-3.0", formatValue(-3.0, p(0.5)))
        assertEquals("0.1", formatValue(0.06, p(null)))
    }

    @Test
    fun searchIsLocalAndIgnoresAccents() {
        assertEquals("Cairo", Places.search("cai").first().name)
        assertEquals("São Paulo", Places.search("sao").first().name)
        assertTrue(Places.search("").isEmpty())
        assertTrue(Places.search("zzz").isEmpty())
    }

    @Test
    fun englishCopyFollowsTheCopyRules() {
        val s = DiscoverStrings.English
        val all = listOf(
            s.searchPlaces, s.addSensor, s.more, s.windowLastHour, s.window24Hours, s.window7Days, s.sampleData,
            s.osm, s.noAreas, s.notMeasured, s.stale, s.firstRunLine, s.explore, s.allMeasurements, s.unavailable,
            s.loading, s.noPlaces, s.clearSearch, s.updatedRecently, s.areasInView(3), s.sensors(1), s.updatedHoursAgo(2),
        ) + listOf(s.sampleInfo, s.scaleInfo, s.mapInfo).flatMap { it.body + it.title }
        val broken = all.filter { '—' in it || '–' in it || ';' in it || ':' in it }
        assertTrue(broken.isEmpty(), "copy rule violations $broken")
    }
}
