@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package earth.walkthru.opensensor

import earth.walkthru.aqsync.archive.ArchiveIndex
import earth.walkthru.aqsync.archive.ArchiveKey
import earth.walkthru.aqsync.archive.ArchiveRecord
import earth.walkthru.aqsync.archive.IosArchiveStore
import earth.walkthru.aqsync.data.history.ArchiveIndexRepair
import earth.walkthru.aqsync.data.history.HistoryOrchestrator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The iOS clock anchor follow-up (#137). It runs the shared [HistoryOrchestrator.afterClockReceipt],
 * so the time estimates wait for a History pass in flight and the derive comes after them.
 */
class ClockAnchorFollowUpTest {
    /** An empty index that counts the estimate's station reads, the first thing an estimate does. */
    private class CountingIndex : ArchiveIndex {
        var stationReads = 0
        override fun observeAll(): Flow<List<ArchiveRecord>> = MutableStateFlow(emptyList())
        override fun observeStation(station: String): Flow<List<ArchiveRecord>> = MutableStateFlow(emptyList())
        override suspend fun get(key: ArchiveKey): ArchiveRecord? = null
        override suspend fun put(record: ArchiveRecord) = error("the estimate of an empty index writes nothing")
        override suspend fun remove(key: ArchiveKey) = error("the estimate of an empty index removes nothing")
        override suspend fun unindexed(limit: Int): List<ArchiveRecord> = emptyList()
        override suspend fun timeline(station: String, fromNs: Long, toNs: Long): List<ArchiveRecord> = emptyList()
        override suspend fun indexedForStation(station: String): List<ArchiveRecord> = emptyList()
        override suspend fun stations(): List<String> {
            stationReads++
            return emptyList()
        }
    }

    @Test
    fun theFollowUpEstimatesInsideAPassThenDerives() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        // Never opened, the estimate of an empty index reads no file.
        val base = NSTemporaryDirectory().trimEnd('/') + "/anchor-follow-up-" + NSUUID().UUIDString()
        val index = CountingIndex()
        val history = HistoryOrchestrator(ArchiveIndexRepair(IosArchiveStore("$base/final", "$base/partial", dispatcher), index, io = dispatcher), index)
        val events = ArrayList<String>()
        val followUp = clockAnchorFollowUp(CoroutineScope(backgroundScope.coroutineContext + dispatcher), history) {
            history.derivePass { events += "derive after ${index.stationReads} estimate" }
        }

        val inPass = CompletableDeferred<Unit>()
        val endPass = CompletableDeferred<Unit>()
        val pass = launch(dispatcher) { history.derivePass { inPass.complete(Unit); endPass.await() } }
        inPass.await()

        followUp.request()
        runCurrent()
        assertEquals(0, index.stationReads, "the estimates wait for the pass in flight")
        assertTrue(events.isEmpty(), "the derive waits for the estimates")

        endPass.complete(Unit)
        pass.join()
        runCurrent()
        assertEquals(listOf("derive after 1 estimate"), events)
    }
}
