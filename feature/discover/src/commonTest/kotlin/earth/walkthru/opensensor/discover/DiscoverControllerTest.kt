package earth.walkthru.opensensor.discover

import earth.walkthru.aqsync.airquality.WhoStep
import earth.walkthru.aqsync.publicfeed.CellRow
import earth.walkthru.aqsync.publicfeed.CellStat
import earth.walkthru.aqsync.publicfeed.CellsFileInfo
import earth.walkthru.aqsync.publicfeed.CellsTable
import earth.walkthru.aqsync.publicfeed.FeedPartition
import earth.walkthru.aqsync.publicfeed.FeedProperty
import earth.walkthru.aqsync.publicfeed.FeedWindow
import earth.walkthru.aqsync.publicfeed.GeoBounds
import earth.walkthru.aqsync.publicfeed.H3Grid
import earth.walkthru.aqsync.publicfeed.H3Index
import earth.walkthru.aqsync.publicfeed.LatLng
import earth.walkthru.aqsync.publicfeed.PublicFeed
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscoverControllerTest {
    private val cellA = 0x85283473fffffffL
    private val cellB = 0x8528347bfffffffL
    private val pm25 = FeedProperty("pm25", "PM2.5", "µg/m³", "Air")

    /** pm25 per window. A is 10, 30 and 80 (7 days, 24 hours, last hour). B is 5 in every window. */
    private fun value(cell: Long, window: FeedWindow): Double = when {
        cell == cellB -> 5.0
        window == FeedWindow.WEEK -> 10.0
        window == FeedWindow.DAY -> 30.0
        else -> 80.0
    }

    private inner class FakeFeed : PublicFeed {
        override val isSample = true
        override val available = true
        val calls = mutableListOf<Pair<FeedWindow, List<LongRange>?>>()
        val gates = mutableMapOf<Long, CompletableDeferred<Unit>>()

        override suspend fun latest() = FeedPartition("2026-10-12", 10)

        override suspend fun cells(partition: FeedPartition, window: FeedWindow, res: Int, ranges: List<LongRange>?): CellsTable {
            calls += window to ranges
            ranges?.singleOrNull()?.takeIf { it.first == it.last }?.let { gates[it.first]?.await() }
            val rows = listOf(cellA, cellB)
                .filter { c -> ranges == null || ranges.any { c in it } }
                .map { c -> CellRow(c, devices = 3, updatedEpochMs = 1_000L, stats = mapOf("pm25" to CellStat(3, value(c, window), null, null))) }
            return CellsTable(CellsFileInfo(sample = true, window = null, h3Res = res, properties = listOf(pm25)), rows, 1, 1)
        }
    }

    /** Every viewport covers A and B. Outlines are a small triangle, enough for the GeoJSON. */
    private object FakeGrid : H3Grid {
        override fun latLngToCell(lat: Double, lng: Double, res: Int) = 0x85283473fffffffL
        override fun cellToLatLng(cell: Long) = LatLng(37.0, -122.0)
        override fun cellToBoundary(cell: Long) = listOf(LatLng(37.0, -122.0), LatLng(37.1, -122.0), LatLng(37.0, -122.1))
        override fun polygonToCells(ring: List<LatLng>, res: Int) = listOf(0x85283473fffffffL, 0x8528347bfffffffL)
    }

    private class MemoryPrefs : DiscoverPrefs {
        var saved = DiscoverSaved(firstRunDone = true)
        override suspend fun load() = saved
        override suspend fun save(saved: DiscoverSaved) {
            this.saved = saved
        }
    }

    /**
     * The controller runs in backgroundScope, which advanceUntilIdle does not drive. Past the 400 ms
     * camera rest, then everything due now.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.settle() {
        advanceTimeBy(1_000)
        runCurrent()
    }

    private fun TestScope.controller(feed: FakeFeed): DiscoverController {
        val c = DiscoverController(backgroundScope, feed, FakeGrid, MemoryPrefs(), "UTC", now = { 1_000L }, io = StandardTestDispatcher(testScheduler))
        // Zoom 6.25 reads resolution 5, the resolution of both test cells.
        c.onCamera(CameraRest(GeoBounds(-122.01, 36.99, -121.99, 37.01), zoom = 6.25, lat = 37.0, lng = -122.0))
        settle()
        return c
    }

    @Test
    fun selectingACellReadsItsOtherTwoWindowsForThatCellOnly() = runTest {
        val feed = FakeFeed()
        val c = controller(feed)
        feed.calls.clear()
        c.selectCell(H3Index.toHex(cellA))
        settle()
        assertEquals(setOf(FeedWindow.WEEK, FeedWindow.LAST_HOUR), feed.calls.map { it.first }.toSet())
        assertTrue(feed.calls.all { it.second == listOf(cellA..cellA) })
        val windows = c.state.value.selectedWindows
        assertEquals(setOf(FeedWindow.WEEK, FeedWindow.DAY, FeedWindow.LAST_HOUR), windows.keys)
        assertTrue(windows.values.all { it?.h3 == cellA })
        val summary = c.state.value.selectedSummary(pm25)!!
        assertEquals(listOf(WhoStep.GUIDELINE, WhoStep.IT3, WhoStep.ABOVE_IT1), summary.blocks.map { it.step })
        assertEquals(30.0, summary.value)
    }

    @Test
    fun aSlowReadForAnEarlierCellNeverLandsOnTheNewOne() = runTest {
        val feed = FakeFeed()
        val c = controller(feed)
        val slow = CompletableDeferred<Unit>()
        feed.gates[cellA] = slow
        c.selectCell(H3Index.toHex(cellA))
        settle()
        c.selectCell(H3Index.toHex(cellB))
        settle()
        slow.complete(Unit)
        settle()
        val windows = c.state.value.selectedWindows
        assertEquals(cellB, c.state.value.selected?.h3)
        assertEquals(3, windows.size)
        assertTrue(windows.values.all { it?.h3 == cellB }, "stale rows $windows")
    }

    @Test
    fun clearingTheSelectionClearsTheWindows() = runTest {
        val c = controller(FakeFeed())
        c.selectCell(H3Index.toHex(cellA))
        settle()
        c.selectCell(null)
        settle()
        assertTrue(c.state.value.selectedWindows.isEmpty())
        assertNull(c.state.value.selectedSummary(pm25))
    }

    @Test
    fun changingTheWindowRereadsTheOthersForTheSelectedCell() = runTest {
        val feed = FakeFeed()
        val c = controller(feed)
        c.selectCell(H3Index.toHex(cellA))
        settle()
        feed.calls.clear()
        c.selectWindow(FeedWindow.LAST_HOUR)
        settle()
        val cellReads = feed.calls.filter { it.second == listOf(cellA..cellA) }.map { it.first }.toSet()
        assertEquals(setOf(FeedWindow.WEEK, FeedWindow.DAY), cellReads)
        assertEquals(3, c.state.value.selectedWindows.size)
        assertEquals(30.0, c.state.value.selectedSummary(pm25)?.value)
    }

    @Test
    fun aCellTappedBeforeTheNewWindowLoadsNeverShowsTheOldWindowsValue() = runTest {
        val c = controller(FakeFeed())
        c.selectWindow(FeedWindow.WEEK)
        // The rows on screen are still the 24 hour ones, now filed under the 7 day window.
        c.selectCell(H3Index.toHex(cellA))
        assertTrue(c.state.value.selectedWindows.isEmpty())
        assertNull(c.state.value.selectedSummary(pm25)?.value)
        settle()
        assertEquals(30.0, c.state.value.selectedSummary(pm25)?.value)
        assertEquals(10.0, c.state.value.selectedWindows[FeedWindow.WEEK]?.mean("pm25"))
        assertEquals(3, c.state.value.selectedWindows.size)
    }

    @Test
    fun aPanAtTheSameCellWindowAndResolutionDoesNotRereadTheWindows() = runTest {
        val feed = FakeFeed()
        val c = controller(feed)
        c.selectCell(H3Index.toHex(cellA))
        settle()
        feed.calls.clear()
        c.onCamera(CameraRest(GeoBounds(-122.02, 36.98, -121.98, 37.02), zoom = 6.25, lat = 37.0, lng = -122.0))
        settle()
        assertTrue(feed.calls.isNotEmpty(), "the pan reads the viewport")
        assertTrue(feed.calls.none { it.second == listOf(cellA..cellA) }, "reread ${feed.calls}")
        assertEquals(3, c.state.value.selectedWindows.size)
        assertEquals(30.0, c.state.value.selectedSummary(pm25)?.value)
    }
}
