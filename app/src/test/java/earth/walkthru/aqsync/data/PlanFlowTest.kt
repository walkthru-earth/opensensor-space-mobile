package earth.walkthru.aqsync.data

import app.cash.turbine.test
import earth.walkthru.aqsync.archive.PrunedFile
import earth.walkthru.aqsync.data.local.database.ArchivedFileIdentity
import earth.walkthru.aqsync.data.protocol.DeviceInfo
import earth.walkthru.aqsync.data.protocol.Frame
import earth.walkthru.aqsync.data.sync.FileOutcome
import earth.walkthru.aqsync.data.sync.FileProgress
import earth.walkthru.aqsync.data.sync.FileSyncState
import earth.walkthru.aqsync.data.sync.SyncPlan
import earth.walkthru.aqsync.data.sync.SyncPlanner
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlanFlowTest {
    private val station = "station-a"
    private val name = "station=$station/year=2026/month=10/day=11/data_0900.parquet"
    private val second = "station=$station/year=2026/month=10/day=11/data_0915.parquet"
    private val size = 1_000_000L

    private fun listing(vararg names: String) = ManifestState.Loaded(names.map { Frame.File(size, it) }, Frame.ListEnd(names.size.toLong(), 0, 0, 0), 0)

    /** A plan flow over fakes that counts planner and partial-directory calls. */
    private class Harness(station: String, listing: ManifestState) {
        val manifest = MutableStateFlow(listing)
        val info = MutableStateFlow<DeviceInfo?>(DeviceInfo(station = station))
        val archives = mutableMapOf<String, MutableStateFlow<List<ArchivedFileIdentity>>>()
        val sync = MutableStateFlow(SyncProgress())
        val pruned = MutableStateFlow<List<PrunedFile>>(emptyList())
        val observed = mutableListOf<String>()
        var plans = 0
        var partials = 0

        fun archive(station: String) = archives.getOrPut(station) { MutableStateFlow(emptyList()) }

        val plan: Flow<SyncPlan?> = planWithProgress(
            manifest,
            info,
            archived = { s -> observed += s; archive(s) },
            sync = sync,
            partialLengths = { partials++; emptyMap() },
            pruned = pruned,
            planner = { s, files, ids, p, r -> plans++; SyncPlanner.plan(s, files, ids, p, r) },
        )

        fun tick(file: String, done: Long) {
            sync.value = sync.value.copy(current = FileProgress(file, bytesDone = done, bytesTotal = 1_000_000, resumedFrom = 0), bytesDone = done)
        }
    }

    @Test
    fun `progress ticks reuse the base plan and only redo the overlay`() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(station, listing(name))
        var last: SyncPlan? = null
        val job = h.plan.onEach { last = it }.launchIn(backgroundScope)

        h.sync.value = SyncProgress(running = true, filesTotal = 1, bytesTotal = size)
        val (plansBefore, partialsBefore) = h.plans to h.partials
        repeat(1_000) { tick -> h.tick(name, tick + 1L) }
        advanceTimeBy(PROGRESS_SAMPLE_MS + 1)

        assertEquals(plansBefore, h.plans)
        assertEquals(partialsBefore, h.partials)
        assertEquals(FileSyncState.SYNCING, last?.entries?.single()?.state)
        assertEquals(1_000L, last?.entries?.single()?.partialBytes)

        // Stopping the run rebuilds the base once, so a partial written meanwhile is read again.
        h.sync.value = h.sync.value.copy(running = false, current = null)
        assertEquals(plansBefore + 1, h.plans)
        assertEquals(partialsBefore + 1, h.partials)
        job.cancel()
    }

    @Test
    fun `the planner runs once per manifest or archive change and never on a tick`() = runTest {
        val h = Harness(station, listing(name))
        h.plan.test {
            assertEquals(1, awaitItem()?.entries?.size)
            assertEquals(1 to 1, h.plans to h.partials)

            h.manifest.value = listing(name, second)
            assertEquals(2, awaitItem()?.entries?.size)
            assertEquals(2 to 2, h.plans to h.partials)

            h.archive(station).value = listOf(ArchivedFileIdentity(station, name, size, 7))
            assertEquals(FileSyncState.SYNCED, awaitItem()!!.entries.first { it.name == name }.state)
            assertEquals(3 to 3, h.plans to h.partials)

            // A run starts: the running flag rebuilds the base once, then a thousand ticks do not.
            h.sync.value = SyncProgress(running = true, filesTotal = 1, bytesTotal = size)
            awaitItem()
            val (plans, partials) = h.plans to h.partials
            assertEquals(4 to 4, plans to partials)
            repeat(1_000) { h.tick(second, it + 1L) }
            assertEquals(1_000L, expectMostRecentItemAfter(PROGRESS_SAMPLE_MS)!!.entries.first { it.name == second }.partialBytes)
            assertEquals(plans to partials, h.plans to h.partials)

            // Another station's archive is not observed at all.
            assertEquals(listOf(station), h.observed)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a removed file shows as removed until its record is cleared`() = runTest {
        val h = Harness(station, listing(name, second))
        h.plan.test {
            assertEquals(FileSyncState.NOT_SYNCED, awaitItem()!!.entries.first { it.name == name }.state)
            h.pruned.value = listOf(PrunedFile(station, name, size, 7, 1))
            val removed = awaitItem()!!
            assertEquals(FileSyncState.REMOVED, removed.entries.first { it.name == name }.state)
            assertEquals(listOf(second), removed.toDownload.map { it.name })
            h.pruned.value = emptyList()
            assertEquals(FileSyncState.NOT_SYNCED, awaitItem()!!.entries.first { it.name == name }.state)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a station switch plans against the new station only`() = runTest {
        val h = Harness(station, listing(name))
        h.archive("station-b").value = listOf(ArchivedFileIdentity("station-b", name, size, 7))
        h.plan.test {
            assertEquals(FileSyncState.NOT_SYNCED, awaitItem()!!.entries.single().state)
            h.info.value = DeviceInfo(station = "station-b")
            assertEquals(FileSyncState.SYNCED, awaitItem()!!.entries.single().state)
            assertEquals(listOf(station, "station-b"), h.observed)
            val plans = h.plans
            h.archive(station).value = listOf(ArchivedFileIdentity(station, name, size, 8))
            runCurrent()
            expectNoEvents()
            assertEquals(plans, h.plans)
            h.info.value = null
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `ticks are sampled and the end of a run is emitted at once`() = runTest {
        val h = Harness(station, listing(name, second))
        h.plan.test {
            awaitItem()
            // The running flag reaches both the base and the overlay, so it may emit twice.
            h.sync.value = SyncProgress(running = true, filesTotal = 2, bytesTotal = 2 * size)
            runCurrent()
            assertEquals(FileSyncState.NOT_SYNCED, expectMostRecentItem()!!.entries.first().state)

            // The first tick of a file passes at once, later ticks wait for the window.
            h.tick(name, 10)
            assertEquals(10L, awaitItem()!!.entries.first { it.name == name }.partialBytes)
            h.tick(name, 20)
            h.tick(name, 30)
            expectNoEvents()
            advanceTimeBy(PROGRESS_SAMPLE_MS + 1)
            assertEquals(30L, awaitItem()!!.entries.first { it.name == name }.partialBytes)

            // A pending tick never outlives the outcome that follows it.
            h.tick(name, 40)
            val failed = FileOutcome.Mismatch(second, "crc")
            h.sync.value = h.sync.value.copy(outcomes = listOf(failed))
            val withOutcome = awaitItem()!!
            assertEquals(FileSyncState.MISMATCH, withOutcome.entries.first { it.name == second }.state)
            assertEquals(40L, withOutcome.entries.first { it.name == name }.partialBytes)

            h.tick(name, 50)
            // The end of the run rebuilds the base too, the newest item is the finished overlay.
            h.sync.value = h.sync.value.copy(running = false, current = null)
            runCurrent()
            val done = expectMostRecentItem()!!
            assertFalse(done.entries.any { it.state == FileSyncState.SYNCING })
            assertEquals(FileSyncState.MISMATCH, done.entries.first { it.name == second }.state)
            advanceTimeBy(10 * PROGRESS_SAMPLE_MS)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `sampling keeps the newest tick of each window and flushes a finished upstream`() = runTest {
        val ticks = (1..10L).map { SyncProgress(running = true, bytesDone = it) }
        flowOf(*ticks.toTypedArray()).sampleTicks(PROGRESS_SAMPLE_MS).test {
            assertEquals(1L, awaitItem().bytesDone)
            assertEquals(10L, awaitItem().bytesDone)
            awaitComplete()
        }
    }

    @Test
    fun `moving to a new file emits immediately and cancels the old pending tick`() = runTest {
        val progress = MutableStateFlow(SyncProgress(running = true))
        progress.sampleTicks(PROGRESS_SAMPLE_MS).test {
            awaitItem()
            progress.value = progress.value.copy(current = FileProgress(name, 10, size, 0), bytesDone = 10)
            assertEquals(name, awaitItem().current!!.name)
            progress.value = progress.value.copy(current = FileProgress(name, 20, size, 0), bytesDone = 20)
            runCurrent()
            expectNoEvents()

            progress.value = progress.value.copy(current = FileProgress(second, 5, size, 0), bytesDone = 25)
            val next = awaitItem()
            assertEquals(second, next.current!!.name)
            assertEquals(5L, next.current!!.bytesDone)
            advanceTimeBy(PROGRESS_SAMPLE_MS + 1)
            expectNoEvents()

            progress.value = progress.value.copy(current = FileProgress(second, 15, size, 0), bytesDone = 35)
            advanceTimeBy(PROGRESS_SAMPLE_MS - 1)
            expectNoEvents()
            advanceTimeBy(2)
            assertEquals(15L, awaitItem().current!!.bytesDone)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private suspend fun app.cash.turbine.ReceiveTurbine<SyncPlan?>.expectMostRecentItemAfter(ms: Long): SyncPlan? {
        kotlinx.coroutines.delay(ms + 1)
        return expectMostRecentItem()
    }
}
