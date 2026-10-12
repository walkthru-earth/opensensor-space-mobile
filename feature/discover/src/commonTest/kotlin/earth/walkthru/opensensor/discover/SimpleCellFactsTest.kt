package earth.walkthru.opensensor.discover

import earth.walkthru.aqsync.publicfeed.CellRow
import earth.walkthru.aqsync.publicfeed.CellStat
import earth.walkthru.aqsync.publicfeed.FeedProperty
import earth.walkthru.aqsync.publicfeed.FeedWindow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Simple cell sheet reads the 24 hour row only, never the row of the window on screen. */
class SimpleCellFactsTest {
    private val pm = FeedProperty("pm25", "PM2.5", "µg/m³", "Air")
    private val temp = FeedProperty("temp", "Temp", "°C", "Weather")
    private val noise = FeedProperty("noise", "Noise", "dB", "Noise")

    private fun row(devices: Int, updated: Long, vararg means: Pair<String, Double>) =
        CellRow(1L, devices, updated, means.associate { (k, v) -> k to CellStat(3, v, v, v) })

    @Test
    fun theSheetReadsTheDayRowNotTheWindowOnScreen() {
        val hour = row(9, 2_000L, "pm25" to 80.0, "temp" to 30.0)
        val day = row(4, 1_000L, "pm25" to 30.0, "temp" to 21.0)
        val state = DiscoverUiState(
            dictionary = listOf(pm, temp, noise),
            property = pm,
            selected = hour,
            selectedWindows = mapOf(FeedWindow.LAST_HOUR to hour, FeedWindow.DAY to day),
        )
        val facts = state.simpleCellFacts()
        assertEquals(4, facts.devices)
        assertEquals(1_000L, facts.updatedEpochMs)
        assertEquals(listOf(temp to 21.0), facts.others)
    }

    @Test
    fun beforeTheDayRowIsReadTheSheetIsLoadingNotNotAvailable() {
        val old = row(9, 2_000L, "pm25" to 80.0, "temp" to 30.0)
        val state = DiscoverUiState(
            dictionary = listOf(pm, temp, noise),
            property = pm,
            selected = old,
            selectedWindows = mapOf(FeedWindow.LAST_HOUR to old),
        )
        assertEquals(SimpleHeadline.LOADING, state.simpleHeadline())
        val facts = state.simpleCellFacts()
        assertTrue(facts.loading)
        assertNull(facts.devices)
        assertNull(facts.updatedEpochMs)
        assertTrue(facts.others.isEmpty())
    }

    @Test
    fun noWindowReadYetIsLoadingNotNotMeasured() {
        val state = DiscoverUiState(dictionary = listOf(pm), property = pm, selected = row(1, 1L), selectedWindows = emptyMap())
        assertEquals(SimpleHeadline.LOADING, state.simpleHeadline())
    }

    @Test
    fun aDayRowReadWithoutTheStatShowsTheHeadlineAsNotAvailable() {
        val hour = row(9, 2_000L, "pm25" to 80.0, "temp" to 30.0)
        val day = row(4, 1_000L, "noise" to 40.0)
        val state = DiscoverUiState(
            dictionary = listOf(pm, temp, noise),
            property = pm,
            selected = hour,
            selectedWindows = mapOf(FeedWindow.LAST_HOUR to hour, FeedWindow.DAY to day),
        )
        assertEquals(SimpleHeadline.SHOWN, state.simpleHeadline())
        val facts = state.simpleCellFacts()
        assertFalse(facts.loading)
        assertEquals(listOf<Pair<FeedProperty, Double?>>(temp to null, noise to 40.0), facts.others)
    }

    @Test
    fun aDayReadWithNoRowAndNoStatAnywhereIsNotMeasured() {
        val hour = row(9, 2_000L, "temp" to 30.0)
        val state = DiscoverUiState(
            dictionary = listOf(pm, temp, noise),
            property = pm,
            selected = hour,
            selectedWindows = mapOf(FeedWindow.LAST_HOUR to hour, FeedWindow.DAY to null),
        )
        assertEquals(SimpleHeadline.NOT_MEASURED, state.simpleHeadline())
        val facts = state.simpleCellFacts()
        assertFalse(facts.loading)
        assertNull(facts.devices)
        assertEquals(listOf<Pair<FeedProperty, Double?>>(temp to null), facts.others)
    }

    @Test
    fun aDayReadThatFailedIsFailedNotLoading() {
        val hour = row(9, 2_000L, "pm25" to 80.0)
        val state = DiscoverUiState(
            dictionary = listOf(pm),
            property = pm,
            selected = hour,
            selectedWindows = mapOf(FeedWindow.LAST_HOUR to hour),
            failedWindows = setOf(FeedWindow.DAY),
        )
        assertEquals(SimpleHeadline.FAILED, state.simpleHeadline())
        assertTrue(state.simpleCellFacts().loading)
    }

    @Test
    fun aFailedWindowIsNeverTakenAsNotMeasured() {
        val hour = row(9, 2_000L, "temp" to 30.0)
        val state = DiscoverUiState(
            dictionary = listOf(pm, temp),
            property = pm,
            selected = hour,
            selectedWindows = mapOf(FeedWindow.LAST_HOUR to hour, FeedWindow.DAY to null),
            failedWindows = setOf(FeedWindow.WEEK),
        )
        assertEquals(SimpleHeadline.FAILED, state.simpleHeadline())
    }
}
