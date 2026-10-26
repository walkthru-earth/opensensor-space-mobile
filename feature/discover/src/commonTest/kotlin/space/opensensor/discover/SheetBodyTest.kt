package space.opensensor.discover

import space.opensensor.publicfeed.CellRow
import kotlin.test.Test
import kotlin.test.assertEquals

/** What the Discover sheet shows for a state, the order of [sheetBody]. */
class SheetBodyTest {
    private val row = CellRow(0x85283473fffffffL, devices = 3, updatedEpochMs = 1_000L, stats = emptyMap())

    @Test
    fun aFailedLoadShowsItsRetryEvenBeforeTheFirstRunCardIsDismissed() {
        val s = DiscoverUiState(firstRun = true, loadFailed = true)
        assertEquals(SheetBody.LOAD_FAILED, s.sheetBody(showAll = false, simple = true))
    }

    @Test
    fun theFirstRunCardShowsWhenNothingFailed() {
        assertEquals(SheetBody.FIRST_RUN, DiscoverUiState(firstRun = true).sheetBody(showAll = false, simple = false))
    }

    @Test
    fun aSelectedCellAndUnavailableKeepTheirPlace() {
        assertEquals(SheetBody.UNAVAILABLE, DiscoverUiState(available = false, loadFailed = true).sheetBody(showAll = false, simple = false))
        assertEquals(SheetBody.SIMPLE_CELL, DiscoverUiState(selected = row, firstRun = true).sheetBody(showAll = false, simple = true))
        assertEquals(SheetBody.CELL, DiscoverUiState(selected = row).sheetBody(showAll = false, simple = false))
        assertEquals(SheetBody.ALL_PROPERTIES, DiscoverUiState(selected = row).sheetBody(showAll = true, simple = false))
        assertEquals(SheetBody.IN_VIEW, DiscoverUiState().sheetBody(showAll = false, simple = false))
    }
}
