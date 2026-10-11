package earth.walkthru.aqsync.data

import earth.walkthru.aqsync.data.local.database.ArchivedFile
import earth.walkthru.aqsync.data.protocol.DeviceInfo
import earth.walkthru.aqsync.data.protocol.Frame
import earth.walkthru.aqsync.data.sync.FileProgress
import earth.walkthru.aqsync.data.sync.FileSyncState
import earth.walkthru.aqsync.data.sync.SyncPlanner
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlanFlowTest {
    private val station = "station-a"
    private val name = "station=$station/year=2026/month=10/day=11/data_0900.parquet"

    @Test
    fun `progress ticks reuse the base plan and only redo the overlay`() = runTest(UnconfinedTestDispatcher()) {
        val manifest = MutableStateFlow<ManifestState>(ManifestState.Loaded(listOf(Frame.File(1_000_000, name)), Frame.ListEnd(1, 0, 0, 0), 0))
        val info = MutableStateFlow<DeviceInfo?>(DeviceInfo(station = station))
        val archived = MutableStateFlow<List<ArchivedFile>>(emptyList())
        val sync = MutableStateFlow(SyncProgress())
        var plans = 0
        var partials = 0
        var last: earth.walkthru.aqsync.data.sync.SyncPlan? = null
        val job = planWithProgress(
            manifest,
            info,
            archived,
            sync,
            partialLengths = { partials++; emptyMap() },
            planner = { s, files, ids, p -> plans++; SyncPlanner.plan(s, files, ids, p) },
        ).onEach { last = it }.launchIn(backgroundScope)

        sync.value = SyncProgress(running = true, filesTotal = 1, bytesTotal = 1_000_000)
        val (plansBefore, partialsBefore) = plans to partials
        repeat(1_000) { tick ->
            sync.value = sync.value.copy(current = FileProgress(name, bytesDone = tick + 1L, bytesTotal = 1_000_000, resumedFrom = 0), bytesDone = tick + 1L)
        }

        assertEquals(plansBefore, plans)
        assertEquals(partialsBefore, partials)
        assertEquals(FileSyncState.SYNCING, last?.entries?.single()?.state)
        assertEquals(1_000L, last?.entries?.single()?.partialBytes)

        // Stopping the run rebuilds the base once, so a partial written meanwhile is read again.
        sync.value = sync.value.copy(running = false, current = null)
        assertEquals(plansBefore + 1, plans)
        assertEquals(partialsBefore + 1, partials)
        job.cancel()
    }
}
