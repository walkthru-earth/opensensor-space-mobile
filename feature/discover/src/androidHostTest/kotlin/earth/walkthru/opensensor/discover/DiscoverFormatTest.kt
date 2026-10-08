package earth.walkthru.opensensor.discover

import earth.walkthru.aqsync.publicfeed.FeedProperty
import earth.walkthru.aqsync.units.UnitFormatter
import earth.walkthru.aqsync.units.UnitSystem
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
    fun imperialConvertsLabelsOnly() {
        val imperial = UnitFormatter(UnitSystem.IMPERIAL)
        val temp = FeedProperty("temperature", "Temperature", "°C", "Weather", 0.5)
        val pm = FeedProperty("pm25", "PM2.5", "µg/m³", "Air", 1.0)
        assertEquals("70.7", formatValue(21.5, temp, imperial))
        assertEquals("°F", unitLabel(temp, imperial))
        assertEquals("21.5", formatValue(21.5, temp))
        assertEquals("°C", unitLabel(temp))
        assertEquals("14", formatValue(13.6, pm, imperial))
        assertEquals("µg/m³", unitLabel(pm, imperial))
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
            s.higherThan(40), s.rangeSummary("PM2.5", "14", "6", "31", "µg/m³"), s.rangeSummary("PM2.5", "14", null, null, "µg/m³"),
            s.spreadSummary("PM2.5", 12, "4", "40", "µg/m³"),
        ) + listOf(s.sampleInfo, s.scaleInfo, s.mapInfo).flatMap { it.body + it.title }
        val broken = all.filter { '—' in it || '–' in it || ';' in it || ':' in it }
        assertTrue(broken.isEmpty(), "copy rule violations $broken")
    }
}
