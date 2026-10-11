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

        /** Holds the single-cell reads of one cell, the window reads of a selection. */
        val gates = mutableMapOf<Long, CompletableDeferred<Unit>>()

        /** Holds the next viewport reads, the ones that are not for a single cell. Never a single-cell read. */
        var viewportGate: CompletableDeferred<Unit>? = null

        /** How many more times a single-cell read throws before it succeeds. */
        val failures = mutableMapOf<Pair<Long, FeedWindow>, Int>()

        override suspend fun latest() = FeedPartition("2026-10-12", 10)

        override suspend fun cells(partition: FeedPartition, window: FeedWindow, res: Int, ranges: List<LongRange>?): CellsTable {
            calls += window to ranges
            val single = ranges?.singleOrNull()?.takeIf { it.first == it.last }?.first
            if (single != null) {
                gates[single]?.await()
                val left = failures[single to window] ?: 0
                if (left > 0) {
                    failures[single to window] = left - 1
                    throw IllegalStateException("read failed")
                }
            } else {
                viewportGate?.await()
            }
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

    /** Past every retry of a failed window read, 1 s then 2 s. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.retriesDone() {
        advanceTimeBy(5_000)
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

    @Test
    fun aTapMadeDuringTheViewportReadIsNotRevertedWhenItLands() = runTest {
        val feed = FakeFeed()
        val c = controller(feed)
        val gate = CompletableDeferred<Unit>()
        feed.viewportGate = gate
        c.onCamera(CameraRest(GeoBounds(-122.02, 36.98, -121.98, 37.02), zoom = 6.25, lat = 37.0, lng = -122.0))
        settle()
        c.selectCell(H3Index.toHex(cellA))
        gate.complete(Unit)
        settle()
        assertEquals(cellA, c.state.value.selected?.h3)
    }

    @Test
    fun aWindowReadThatFailsEveryRetryLeavesItsKeyOutAndThePanReadsItAgain() = runTest {
        val feed = FakeFeed()
        val c = controller(feed)
        feed.failures[cellA to FeedWindow.WEEK] = 3
        c.selectCell(H3Index.toHex(cellA))
        retriesDone()
        // A failed read is not "read, no value". Its key stays out, so the sheet does not say n/a.
        assertEquals(setOf(FeedWindow.DAY, FeedWindow.LAST_HOUR), c.state.value.selectedWindows.keys)
        assertEquals(setOf(FeedWindow.WEEK), c.state.value.failedWindows)
        assertEquals(3, feed.calls.count { it.first == FeedWindow.WEEK && it.second == listOf(cellA..cellA) })
        feed.calls.clear()
        c.onCamera(CameraRest(GeoBounds(-122.02, 36.98, -121.98, 37.02), zoom = 6.25, lat = 37.0, lng = -122.0))
        settle()
        assertEquals(listOf(FeedWindow.WEEK), feed.calls.filter { it.second == listOf(cellA..cellA) }.map { it.first })
        assertEquals(10.0, c.state.value.selectedWindows[FeedWindow.WEEK]?.mean("pm25"))
        assertEquals(3, c.state.value.selectedWindows.size)
        assertTrue(c.state.value.failedWindows.isEmpty())
    }

    /** The map on the last hour, so the 24 hour row of a selected cell comes from its own read. */
    private fun TestScope.onTheLastHour(feed: FakeFeed): DiscoverController {
        val c = controller(feed)
        c.selectWindow(FeedWindow.LAST_HOUR)
        settle()
        return c
    }

    @Test
    fun aDayReadThatFailsEveryRetryEndsFailedNeverNotMeasured() = runTest {
        val feed = FakeFeed()
        val c = onTheLastHour(feed)
        feed.failures[cellA to FeedWindow.DAY] = 3
        c.selectCell(H3Index.toHex(cellA))
        settle()
        assertEquals(SimpleHeadline.LOADING, c.state.value.simpleHeadline(), "still retrying")
        retriesDone()
        assertEquals(3, feed.calls.count { it.first == FeedWindow.DAY && it.second == listOf(cellA..cellA) })
        assertEquals(setOf(FeedWindow.DAY), c.state.value.failedWindows)
        assertEquals(SimpleHeadline.FAILED, c.state.value.simpleHeadline())
    }

    @Test
    fun aDayReadThatFailsOnceLandsAfterTheRetry() = runTest {
        val feed = FakeFeed()
        val c = onTheLastHour(feed)
        feed.failures[cellA to FeedWindow.DAY] = 1
        c.selectCell(H3Index.toHex(cellA))
        retriesDone()
        assertEquals(2, feed.calls.count { it.first == FeedWindow.DAY && it.second == listOf(cellA..cellA) })
        assertTrue(c.state.value.failedWindows.isEmpty())
        assertEquals(SimpleHeadline.SHOWN, c.state.value.simpleHeadline())
        assertEquals(30.0, c.state.value.selectedSummary(pm25)?.value)
    }

    @Test
    fun retryFromTheSheetReadsTheFailedWindowAgain() = runTest {
        val feed = FakeFeed()
        val c = onTheLastHour(feed)
        feed.failures[cellA to FeedWindow.DAY] = 3
        c.selectCell(H3Index.toHex(cellA))
        retriesDone()
        assertEquals(SimpleHeadline.FAILED, c.state.value.simpleHeadline())
        feed.calls.clear()
        c.retryWindows()
        assertEquals(SimpleHeadline.LOADING, c.state.value.simpleHeadline(), "a retry shows loading again")
        settle()
        assertEquals(listOf(FeedWindow.DAY), feed.calls.filter { it.second == listOf(cellA..cellA) }.map { it.first })
        assertTrue(c.state.value.failedWindows.isEmpty())
        assertEquals(30.0, c.state.value.selectedSummary(pm25)?.value)
    }

    @Test
    fun aNewSelectionClearsTheFailedWindows() = runTest {
        val feed = FakeFeed()
        val c = onTheLastHour(feed)
        feed.failures[cellA to FeedWindow.DAY] = 3
        c.selectCell(H3Index.toHex(cellA))
        retriesDone()
        c.selectCell(H3Index.toHex(cellB))
        assertTrue(c.state.value.failedWindows.isEmpty())
    }

    @Test
    fun aCancelledWindowReadStopsInsteadOfReadingTheNextWindow() = runTest {
        val feed = FakeFeed()
        val c = controller(feed)
        feed.gates[cellA] = CompletableDeferred()
        c.selectCell(H3Index.toHex(cellA))
        settle()
        assertEquals(1, feed.calls.count { it.second == listOf(cellA..cellA) })
        // Selecting B cancels A's reads. The cancelled read must not go on to A's next window.
        c.selectCell(H3Index.toHex(cellB))
        settle()
        assertEquals(1, feed.calls.count { it.second == listOf(cellA..cellA) }, "reads ${feed.calls}")
        assertEquals(cellB, c.state.value.selected?.h3)
        assertEquals(3, c.state.value.selectedWindows.size)
    }
}
