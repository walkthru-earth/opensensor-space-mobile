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
import earth.walkthru.aqsync.settings.DiscoverSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    private inner class FakeFeed(
        override val isSample: Boolean = true,
        override val windows: List<FeedWindow> = FeedWindow.entries,
        /** What [latest] answers. A newer one is a new day published while the screen is open. */
        var partition: FeedPartition = FeedPartition("2026-10-12", 10),
        override val isDaily: Boolean = false,
        private val updatedMs: Long = 1_000L,
    ) : PublicFeed {
        override val available = true

        /** The partitions this feed has files for, each with what it adds to every [value]. */
        val published = mutableMapOf(partition to 0.0)
        val calls = mutableListOf<Pair<FeedWindow, List<LongRange>?>>()

        /** True makes [latest] throw, like a manifest that cannot be read. */
        var latestFails = false

        /** Holds the next [latest] calls, a slow manifest. */
        var latestGate: CompletableDeferred<Unit>? = null

        /** True makes every viewport read throw. */
        var viewportFails = false

        /** Holds the single-cell reads of one cell, the window reads of a selection. */
        val gates = mutableMapOf<Long, CompletableDeferred<Unit>>()

        /** Holds the next viewport reads, the ones that are not for a single cell. Never a single-cell read. */
        var viewportGate: CompletableDeferred<Unit>? = null

        /** How many more times a single-cell read throws before it succeeds. */
        val failures = mutableMapOf<Pair<Long, FeedWindow>, Int>()

        override suspend fun latest(): FeedPartition {
            latestGate?.await()
            if (latestFails) throw IllegalStateException("manifest failed")
            return partition
        }

        override suspend fun cells(partition: FeedPartition, window: FeedWindow, res: Int, ranges: List<LongRange>?): CellsTable {
            calls += window to ranges
            // Another feed's partition names files this feed does not have.
            val offset = published[partition] ?: throw IllegalStateException("no file for $partition")
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
                if (viewportFails) throw IllegalStateException("viewport failed")
            }
            val rows = listOf(cellA, cellB)
                .filter { c -> ranges == null || ranges.any { c in it } }
                .map { c -> CellRow(c, devices = 3, updatedEpochMs = updatedMs, stats = mapOf("pm25" to CellStat(3, value(c, window) + offset, null, null))) }
            return CellsTable(CellsFileInfo(sample = isSample, window = null, h3Res = res, properties = listOf(pm25)), rows, 1, 1)
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

    private fun TestScope.controller(
        feed: FakeFeed,
        live: FakeFeed? = null,
        saved: MutableList<DiscoverSource>? = null,
        prefs: MemoryPrefs = MemoryPrefs(),
        io: CoroutineDispatcher = StandardTestDispatcher(testScheduler),
        nowMs: Long = 1_000L,
    ): DiscoverController {
        val c = DiscoverController(
            backgroundScope,
            feed,
            FakeGrid,
            prefs,
            "UTC",
            now = { nowMs },
            io = io,
            liveFeed = live,
            saveSource = { saved?.add(it) },
        )
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

    private fun liveFeed() = FakeFeed(isSample = false, windows = listOf(FeedWindow.DAY, FeedWindow.WEEK), partition = FeedPartition("2026-10-08", 0))

    @Test
    fun aPanAfterAFailedWindowKeepsItFailedUntilTheRereadLands() = runTest {
        val feed = FakeFeed()
        val c = onTheLastHour(feed)
        feed.failures[cellA to FeedWindow.DAY] = 3
        c.selectCell(H3Index.toHex(cellA))
        retriesDone()
        assertEquals(SimpleHeadline.FAILED, c.state.value.simpleHeadline())
        feed.failures[cellA to FeedWindow.DAY] = 1
        c.onCamera(CameraRest(GeoBounds(-122.02, 36.98, -121.98, 37.02), zoom = 6.25, lat = 37.0, lng = -122.0))
        settle()
        // The pan rereads the failed window, and the sheet does not flip back to loading meanwhile.
        assertEquals(SimpleHeadline.FAILED, c.state.value.simpleHeadline())
        assertEquals(setOf(FeedWindow.DAY), c.state.value.failedWindows)
        retriesDone()
        assertEquals(SimpleHeadline.SHOWN, c.state.value.simpleHeadline())
        assertTrue(c.state.value.failedWindows.isEmpty())
    }

    @Test
    fun aWindowChangeClearsTheFailedWindows() = runTest {
        val feed = FakeFeed()
        val c = onTheLastHour(feed)
        feed.failures[cellA to FeedWindow.DAY] = 3
        c.selectCell(H3Index.toHex(cellA))
        retriesDone()
        assertEquals(setOf(FeedWindow.DAY), c.state.value.failedWindows)
        c.selectWindow(FeedWindow.WEEK)
        assertTrue(c.state.value.failedWindows.isEmpty())
    }

    @Test
    fun retryWithNothingMissingReadsNothing() = runTest {
        val feed = FakeFeed()
        val c = controller(feed)
        c.selectCell(H3Index.toHex(cellA))
        settle()
        feed.calls.clear()
        c.retryWindows()
        settle()
        assertTrue(feed.calls.isEmpty())
        assertEquals(3, c.state.value.selectedWindows.size)
    }

    @Test
    fun theDayWindowIsReadFirstSoAFailingWindowNeverDelaysIt() = runTest {
        val feed = FakeFeed()
        val c = controller(feed)
        c.selectWindow(FeedWindow.WEEK)
        settle()
        feed.failures[cellA to FeedWindow.LAST_HOUR] = 3
        c.selectCell(H3Index.toHex(cellA))
        runCurrent()
        assertTrue(FeedWindow.DAY in c.state.value.selectedWindows, "the 24 hours lands before the last hour retries")
        assertEquals(SimpleHeadline.SHOWN, c.state.value.simpleHeadline())
    }

    /** Real on the 7 day map with cell A selected, every window read, on 8 Oct. */
    private fun TestScope.realWeekWithCellA(live: FakeFeed): DiscoverController {
        val c = controller(FakeFeed(), live)
        c.onSourceSettings(DiscoverSource.OPENAQ, publicSensors = true)
        settle()
        c.selectWindow(FeedWindow.WEEK)
        settle()
        c.selectCell(H3Index.toHex(cellA))
        settle()
        assertEquals(30.0, c.state.value.selectedSummary(pm25)?.value)
        return c
    }

    /** 9 Oct is published, every value 50 higher, and the camera rests again. */
    private fun TestScope.nextDayAndPan(c: DiscoverController, live: FakeFeed) {
        live.partition = FeedPartition("2026-10-09", 0)
        live.published[live.partition] = 50.0
        c.onCamera(CameraRest(GeoBounds(-122.02, 36.98, -121.98, 37.02), zoom = 6.25, lat = 37.0, lng = -122.0))
        settle()
    }

    @Test
    fun aNewPartitionRereadsEverySelectedWindow() = runTest {
        // The map and its date move to 9 Oct, so the 24 hour headline must too (#108).
        val live = liveFeed()
        val c = realWeekWithCellA(live)
        nextDayAndPan(c, live)
        val s = c.state.value
        assertEquals(LocalDate(2026, 10, 9), s.dataDate)
        assertEquals(60.0, s.selectedWindows[FeedWindow.WEEK]?.mean("pm25"))
        assertEquals(80.0, s.selectedWindows[FeedWindow.DAY]?.mean("pm25"))
        assertEquals(80.0, s.selectedSummary(pm25)?.value)
    }

    @Test
    fun aSlowReadOfTheOldPartitionNeverLandsOverTheNewOne() = runTest {
        val live = liveFeed()
        val c = controller(FakeFeed(), live)
        c.onSourceSettings(DiscoverSource.OPENAQ, publicSensors = true)
        settle()
        c.selectWindow(FeedWindow.WEEK)
        settle()
        // The 24 hours of 8 Oct is held until 9 Oct is on screen.
        val slow = CompletableDeferred<Unit>()
        live.gates[cellA] = slow
        c.selectCell(H3Index.toHex(cellA))
        settle()
        nextDayAndPan(c, live)
        slow.complete(Unit)
        settle()
        assertEquals(80.0, c.state.value.selectedWindows[FeedWindow.DAY]?.mean("pm25"))
        assertEquals(60.0, c.state.value.selectedWindows[FeedWindow.WEEK]?.mean("pm25"))
    }

    @Test
    fun aRecreatedMapResumesAtTheLastCameraInsteadOfFittingTheRegionAgain() = runTest {
        val c = controller(FakeFeed())
        // First launch, no saved camera, so the map fits the time zone region once.
        assertTrue(c.state.value.start is StartView.Region)
        // After the camera rests, a map built again (a rotation) starts where the camera was.
        assertEquals(StartView.Camera(SavedCamera(37.0, -122.0, 6.25)), c.resumeStart())
    }

    @Test
    fun aRealDailyCellIsJudgedAgainstThePartitionDateNotTheClock() = runTest {
        // Rows that ran to 8 Oct end at midnight UTC on 9 Oct. The phone reads them 98 hours later.
        val endOfDay = LocalDate(2026, 10, 9).toEpochDays() * 86_400_000L
        val live = FakeFeed(
            isSample = false,
            windows = listOf(FeedWindow.DAY, FeedWindow.WEEK),
            partition = FeedPartition("2026-10-08", 0),
            isDaily = true,
            updatedMs = endOfDay,
        )
        val c = controller(FakeFeed(), live, nowMs = endOfDay + 98 * 3_600_000L)
        c.onSourceSettings(DiscoverSource.OPENAQ, publicSensors = true)
        settle()
        val s = c.state.value
        assertTrue(s.daily)
        assertEquals(LocalDate(2026, 10, 8), s.dataDate)
        assertTrue(s.rows.isNotEmpty())
        assertTrue("\"stale\":false" in s.geoJson, s.geoJson)
        assertFalse("\"stale\":true" in s.geoJson, s.geoJson)
    }

    @Test
    fun switchingToRealReadsTheLiveFeedAndClearsEverything() = runTest {
        val sample = FakeFeed()
        val live = liveFeed()
        val c = controller(sample, live)
        c.onSourceSettings(DiscoverSource.SAMPLE, publicSensors = true)
        assertTrue(c.state.value.isSample)
        assertTrue(c.state.value.sourceEnabled)
        c.selectCell(H3Index.toHex(cellA))
        settle()
        assertTrue(live.calls.isEmpty())
        c.onSourceSettings(DiscoverSource.OPENAQ, publicSensors = true)
        val s = c.state.value
        assertFalse(s.isSample)
        assertEquals(DiscoverSource.OPENAQ, s.source)
        assertNull(s.selected)
        assertTrue(s.selectedWindows.isEmpty() && s.failedWindows.isEmpty() && s.rows.isEmpty())
        assertEquals(listOf(FeedWindow.DAY, FeedWindow.WEEK), s.windows)
        sample.calls.clear()
        settle()
        assertTrue(live.calls.isNotEmpty())
        assertTrue(sample.calls.isEmpty())
        assertEquals(2, c.state.value.rows.size)
        assertEquals(LocalDate(2026, 10, 8), c.state.value.dataDate)
        // Back to the sample.
        c.onSourceSettings(DiscoverSource.SAMPLE, publicSensors = true)
        settle()
        assertTrue(c.state.value.isSample)
        assertNull(c.state.value.dataDate)
        assertEquals(FeedWindow.entries, c.state.value.windows)
    }

    @Test
    fun publicSensorsOffKeepsTheSampleAndDisablesTheToggle() = runTest {
        val live = liveFeed()
        val saved = mutableListOf<DiscoverSource>()
        val c = controller(FakeFeed(), live, saved)
        c.onSourceSettings(DiscoverSource.OPENAQ, publicSensors = false)
        settle()
        assertTrue(c.state.value.isSample)
        assertFalse(c.state.value.sourceEnabled)
        c.chooseSource(DiscoverSource.OPENAQ)
        settle()
        assertTrue(c.state.value.isSample)
        assertTrue(saved.isEmpty())
        assertTrue(live.calls.isEmpty(), "no live request while public sensors is off")
    }

    @Test
    fun publicSensorsOffStopsARealReadThatIsRetrying() = runTest {
        val live = liveFeed()
        val c = controller(FakeFeed(), live)
        c.onSourceSettings(DiscoverSource.OPENAQ, publicSensors = true)
        settle()
        FeedWindow.entries.forEach { live.failures[cellA to it] = 3 }
        c.selectCell(H3Index.toHex(cellA))
        runCurrent()
        assertTrue(live.calls.any { it.second == listOf(cellA..cellA) }, "the first window read went out")
        // Settings turns public sensors off while the read waits for its retry.
        c.onSourceSettings(DiscoverSource.OPENAQ, publicSensors = false)
        live.calls.clear()
        retriesDone()
        assertTrue(live.calls.isEmpty(), "no live request after public sensors is off, got ${live.calls}")
        assertTrue(c.state.value.isSample)
    }

    @Test
    fun choosingRealSavesItAndSwitches() = runTest {
        val live = liveFeed()
        val saved = mutableListOf<DiscoverSource>()
        val c = controller(FakeFeed(), live, saved)
        c.onSourceSettings(DiscoverSource.SAMPLE, publicSensors = true)
        c.chooseSource(DiscoverSource.OPENAQ)
        settle()
        assertEquals(listOf(DiscoverSource.OPENAQ), saved)
        assertFalse(c.state.value.isSample)
        assertTrue(live.calls.isNotEmpty())
        // The saved value comes back from the settings flow. Nothing reloads.
        live.calls.clear()
        c.onSourceSettings(DiscoverSource.OPENAQ, publicSensors = true)
        settle()
        assertTrue(live.calls.isEmpty())
    }

    @Test
    fun theLastHourMovesToTheDayInRealMode() = runTest {
        val live = liveFeed()
        val c = controller(FakeFeed(), live)
        c.selectWindow(FeedWindow.LAST_HOUR)
        settle()
        c.onSourceSettings(DiscoverSource.OPENAQ, publicSensors = true)
        assertEquals(FeedWindow.DAY, c.state.value.window)
        c.selectWindow(FeedWindow.LAST_HOUR)
        assertEquals(FeedWindow.DAY, c.state.value.window)
        settle()
        assertTrue(live.calls.none { it.first == FeedWindow.LAST_HOUR })
    }

    @Test
    fun aRealCellReadsOnlyTheRealWindowsAndHasTwoBlocks() = runTest {
        val live = liveFeed()
        val c = controller(FakeFeed(), live)
        c.onSourceSettings(DiscoverSource.OPENAQ, publicSensors = true)
        settle()
        live.calls.clear()
        c.selectCell(H3Index.toHex(cellA))
        settle()
        assertEquals(listOf(FeedWindow.WEEK), live.calls.map { it.first })
        assertEquals(setOf(FeedWindow.DAY, FeedWindow.WEEK), c.state.value.selectedWindows.keys)
        assertEquals(listOf(WhoStep.GUIDELINE, WhoStep.IT3), c.state.value.selectedSummary(pm25)!!.blocks.map { it.step })
        assertEquals(SimpleHeadline.SHOWN, c.state.value.simpleHeadline())
    }

    @Test
    fun aFailedRealLoadIsAnErrorWithRetryNeverTheSample() = runTest {
        val sample = FakeFeed()
        val live = liveFeed().apply { viewportFails = true }
        val c = controller(sample, live)
        sample.calls.clear()
        c.onSourceSettings(DiscoverSource.OPENAQ, publicSensors = true)
        settle()
        val s = c.state.value
        assertTrue(s.loadFailed)
        assertFalse(s.isSample)
        assertFalse(s.loading)
        assertTrue(s.rows.isEmpty())
        assertTrue(sample.calls.isEmpty(), "no silent fallback to the sample")
        live.viewportFails = false
        c.retry()
        assertFalse(c.state.value.loadFailed)
        assertTrue(c.state.value.loading)
        settle()
        assertFalse(c.state.value.loadFailed)
        assertEquals(2, c.state.value.rows.size)
    }

    @Test
    fun anUnreadableManifestIsAnErrorAndRetryReadsItAgain() = runTest {
        val live = liveFeed().apply { latestFails = true }
        val c = controller(FakeFeed(), live)
        c.onSourceSettings(DiscoverSource.OPENAQ, publicSensors = true)
        settle()
        assertTrue(c.state.value.loadFailed)
        assertTrue(live.calls.isEmpty())
        live.latestFails = false
        c.retry()
        settle()
        assertFalse(c.state.value.loadFailed)
        assertEquals(LocalDate(2026, 10, 8), c.state.value.dataDate)
        assertEquals(2, c.state.value.rows.size)
    }

    @Test
    fun withoutALiveFeedTheToggleIsDisabled() = runTest {
        val c = controller(FakeFeed())
        c.onSourceSettings(DiscoverSource.OPENAQ, publicSensors = true)
        assertFalse(c.state.value.sourceEnabled)
        assertTrue(c.state.value.isSample)
    }

    @Test
    fun aSwitchToSampleWhileTheLiveManifestIsAnsweringNeverLeavesTheSampleOnTheLivePartition() = runTest {
        val sample = FakeFeed()
        val live = liveFeed()
        // Unconfined io resumes the gated manifest read at once, so its answer is queued on the
        // controller before the switch back reaches the loader.
        val c = controller(sample, live, io = Dispatchers.Unconfined)
        c.onSourceSettings(DiscoverSource.SAMPLE, publicSensors = true)
        live.latestGate = CompletableDeferred()
        c.onSourceSettings(DiscoverSource.OPENAQ, publicSensors = true)
        settle()
        live.latestGate!!.complete(Unit)
        c.onSourceSettings(DiscoverSource.SAMPLE, publicSensors = true)
        settle()
        val s = c.state.value
        assertTrue(s.isSample)
        assertFalse(s.loading)
        assertEquals(2, s.rows.size, "the sample reads its own partition")
    }

    @Test
    fun choosingTheDayInRealSavesItOverASavedLastHour() = runTest {
        val prefs = MemoryPrefs()
        val c = controller(FakeFeed(), liveFeed(), prefs = prefs)
        c.selectWindow(FeedWindow.LAST_HOUR)
        settle()
        assertEquals(FeedWindow.LAST_HOUR, prefs.saved.window)
        c.onSourceSettings(DiscoverSource.OPENAQ, publicSensors = true)
        settle()
        assertEquals(FeedWindow.DAY, c.state.value.window)
        assertEquals(FeedWindow.LAST_HOUR, prefs.saved.window, "the coercion alone is not saved")
        c.selectWindow(FeedWindow.DAY)
        settle()
        assertEquals(FeedWindow.DAY, prefs.saved.window)
        c.onSourceSettings(DiscoverSource.SAMPLE, publicSensors = true)
        settle()
        assertEquals(FeedWindow.DAY, c.state.value.window)
    }
}
