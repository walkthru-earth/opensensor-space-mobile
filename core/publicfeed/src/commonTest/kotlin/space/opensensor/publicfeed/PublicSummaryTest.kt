package space.opensensor.publicfeed

import space.opensensor.airquality.WhoStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PublicSummaryTest {
    private val cell = 0x85283473fffffffL

    private fun row(pm25: Double?, updated: Long, co2: Double? = null) = CellRow(
        h3 = cell,
        devices = 3,
        updatedEpochMs = updated,
        stats = buildMap {
            put("pm25", CellStat(n = 3, mean = pm25, min = null, max = null))
            if (co2 != null) put("co2", CellStat(n = 3, mean = co2, min = null, max = null))
        },
    )

    @Test
    fun threeWindowsOldestFirstWithTheDayMeanAsHeadline() {
        val s = PublicSummary.of(
            TestProperties.PM25,
            mapOf(FeedWindow.WEEK to row(10.0, 100L), FeedWindow.DAY to row(30.0, 300L), FeedWindow.LAST_HOUR to row(80.0, 200L)),
        )
        assertEquals(listOf(FeedWindow.WEEK, FeedWindow.DAY, FeedWindow.LAST_HOUR), PublicSummary.ORDER)
        assertEquals(listOf(WhoStep.GUIDELINE, WhoStep.IT3, WhoStep.ABOVE_IT1), s.blocks.map { it.step })
        assertEquals(30.0, s.value)
        assertEquals(WhoStep.IT3, s.step)
        assertEquals(300L, s.updatedEpochMs)
    }

    @Test
    fun aFeedWithoutTheLastHourHasTwoBlocks() {
        val windows = mapOf(FeedWindow.WEEK to row(10.0, 100L), FeedWindow.DAY to row(30.0, 300L))
        val s = PublicSummary.of(TestProperties.PM25, windows, offered = listOf(FeedWindow.DAY, FeedWindow.WEEK))
        assertEquals(listOf(WhoStep.GUIDELINE, WhoStep.IT3), s.blocks.map { it.step })
        assertEquals(30.0, s.value)
    }

    @Test
    fun missingWindowsGiveNullBlocks() {
        val s = PublicSummary.of(TestProperties.PM25, mapOf(FeedWindow.LAST_HOUR to row(12.0, 50L), FeedWindow.DAY to null))
        assertEquals(listOf(null, null, WhoStep.GUIDELINE), s.blocks.map { it.step })
        assertNull(s.value)
        assertNull(s.step)
        // The time is the headline window's. A fresh hour never sits beside an n/a headline.
        assertNull(s.updatedEpochMs)
    }

    @Test
    fun theUpdateTimeIsTheDayWindowsEvenWhenAnotherIsNewer() {
        val s = PublicSummary.of(
            TestProperties.PM25,
            mapOf(FeedWindow.DAY to row(30.0, 300L), FeedWindow.LAST_HOUR to row(80.0, 900L)),
        )
        assertEquals(300L, s.updatedEpochMs)
    }

    @Test
    fun aWindowWithoutTheStatIsNotAvailable() {
        val s = PublicSummary.of(TestProperties.PM25, mapOf(FeedWindow.DAY to row(null, 10L)))
        assertNull(s.value)
        assertEquals(listOf(null, null, null), s.blocks.map { it.step })
    }

    @Test
    fun anUnscaledPropertyKeepsItsNumberOnly() {
        val s = PublicSummary.of(TestProperties.CO2, mapOf(FeedWindow.DAY to row(5.0, 10L, co2 = 900.0)))
        assertFalse(s.scaled)
        assertEquals(900.0, s.value)
        assertNull(s.step)
        assertTrue(s.blocks.isEmpty())
    }

    @Test
    fun aPropertyWithoutAWhoScaleHasNoMoodEvenWithSteps() {
        // A Collection scale that is not WHO colours the map but gives no mood blocks.
        val steps = TestProperties.PM25.scale!!.copy(source = "https://example.test/other-scale")
        val s = PublicSummary.of(TestProperties.PM25.copy(scale = steps), mapOf(FeedWindow.DAY to row(30.0, 10L)))
        assertFalse(s.scaled)
        assertEquals(30.0, s.value)
        assertNull(s.step)
        assertTrue(s.blocks.isEmpty())
        // PM2.5 without any scale is neutral too. The id alone never picks WHO.
        assertFalse(PublicSummary.of(TestProperties.PM25.copy(scale = null), mapOf(FeedWindow.DAY to row(30.0, 10L))).scaled)
    }

    @Test
    fun noWindowsMeansNoUpdateTime() {
        assertNull(PublicSummary.of(TestProperties.PM25, emptyMap()).updatedEpochMs)
    }
}
