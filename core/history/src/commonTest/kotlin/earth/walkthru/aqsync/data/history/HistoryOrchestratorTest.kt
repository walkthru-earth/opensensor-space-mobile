package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.archive.ArchiveIndex
import earth.walkthru.aqsync.archive.ArchiveKey
import earth.walkthru.aqsync.archive.ArchiveRecord
import earth.walkthru.aqsync.archive.ArchiveStore
import earth.walkthru.aqsync.archive.PartialMeta
import earth.walkthru.aqsync.archive.PartialWriter
import earth.walkthru.aqsync.data.history.parquet.ByteArrayReader
import earth.walkthru.aqsync.data.history.parquet.ByteReader
import earth.walkthru.aqsync.data.history.parquet.ParquetFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The History pass order both phones share, over an in-memory archive and fake platform passes. */
class HistoryOrchestratorTest {
    private val bytes = fixtureBytes("parquet/device/v3-board-2rg-180rows.parquet")
    private val station = ParquetFile.wrap(bytes).metadata.stationId ?: "orchestrator-station"

    private class Store : ArchiveStore {
        val files = HashMap<ArchiveKey, ByteArray>()
        override suspend fun partialLengths(station: String): Map<String, Long> = emptyMap()
        override suspend fun openPartial(key: ArchiveKey, meta: PartialMeta): PartialWriter = error("read only")
        override suspend fun discardPartial(key: ArchiveKey) = Unit
        override suspend fun exists(key: ArchiveKey): Boolean = key in files
        override fun reader(key: ArchiveKey, partial: Boolean): ByteReader = ByteArrayReader(files.getValue(key))
        override suspend fun commit(key: ArchiveKey) = Unit
        override suspend fun listFinal(station: String?): List<ArchiveKey> = files.keys.filter { station == null || it.station == station }
    }

    /** An index whose row updates can be held, as a slow backfill would be. */
    private class Index : ArchiveIndex {
        val rows = MutableStateFlow<List<ArchiveRecord>>(emptyList())
        var updating: CompletableDeferred<Unit>? = null
        var releaseUpdate: CompletableDeferred<Unit>? = null
        override fun observeAll(): Flow<List<ArchiveRecord>> = rows
        override fun observeStation(station: String): Flow<List<ArchiveRecord>> = rows.map { list -> list.filter { it.station == station } }
        override suspend fun get(key: ArchiveKey): ArchiveRecord? = rows.value.firstOrNull { it.key == key }
        override suspend fun put(record: ArchiveRecord) {
            rows.value = rows.value.filterNot { it.key == record.key } + record
        }
        override suspend fun update(key: ArchiveKey, transform: (ArchiveRecord) -> ArchiveRecord) {
            updating?.complete(Unit)
            releaseUpdate?.await()
            val current = get(key) ?: return
            put(transform(current))
        }
        override suspend fun remove(key: ArchiveKey) {
            rows.value = rows.value.filterNot { it.key == key }
        }
        override suspend fun unindexed(limit: Int): List<ArchiveRecord> = rows.value.filter { !it.isIndexed }.take(limit)
        override suspend fun timeline(station: String, fromNs: Long, toNs: Long): List<ArchiveRecord> =
            rows.value.filter { it.station == station && it.onTimeline(fromNs, toNs) }.sortedWith(ArchiveRecord.TIMELINE_ORDER)
        override suspend fun indexedForStation(station: String): List<ArchiveRecord> = rows.value.filter { it.station == station && it.isIndexed }
        override suspend fun stations(): List<String> = rows.value.map { it.station }.distinct()
    }

    private val store = Store()
    private val index = Index()

    private suspend fun waiting(part: String): ArchiveRecord {
        val r = ArchiveRecord(station, "station=$station/day/$part.parquet", bytes.size.toLong(), 1, "s", 1, "fake")
        store.files[r.key] = bytes
        index.put(r)
        return r
    }

    private fun TestScope.orchestrator(janitor: suspend () -> Unit = {}) =
        HistoryOrchestrator(ArchiveIndexRepair(store, index, io = StandardTestDispatcher(testScheduler), nowEpochMs = { 42L }), index, janitor)

    @Test
    fun aDeriveAskedDuringABackfillWaitsAndSeesTheIndexedRecord() = runTest {
        val record = waiting("a")
        val history = orchestrator()
        index.updating = CompletableDeferred()
        index.releaseUpdate = CompletableDeferred()

        val backfill = launch { history.backfill() }
        index.updating!!.await()
        var sawIndexed: Boolean? = null
        val derive = launch { history.derivePass { sawIndexed = index.get(record.key)!!.isIndexed } }
        runCurrent()
        assertEquals(null, sawIndexed, "the derive waits for the backfill in flight")

        index.releaseUpdate!!.complete(Unit)
        backfill.join()
        derive.join()
        assertEquals(true, sawIndexed)
    }

    @Test
    fun aBackfillAskedDuringADeriveWaitsForIt() = runTest {
        waiting("a")
        val history = orchestrator()
        val inDerive = CompletableDeferred<Unit>()
        val endDerive = CompletableDeferred<Unit>()
        val derive = launch { history.derivePass { inDerive.complete(Unit); endDerive.await() } }
        inDerive.await()

        val backfill = launch { history.backfill() }
        runCurrent()
        assertTrue(index.rows.value.none { it.isIndexed }, "the backfill waits for the derive in flight")
        endDerive.complete(Unit)
        derive.join()
        backfill.join()
        assertTrue(index.rows.value.all { it.isIndexed })
    }

    @Test
    fun runSweepsThenBackfillsThenDerivesAndAgainWhenARecordWaits() = runTest {
        waiting("a")
        val events = ArrayList<String>()
        lateinit var history: HistoryOrchestrator
        history = orchestrator(janitor = {
            events += "janitor ${index.rows.value.count { it.isIndexed }}"
            error("disk busy")
        })
        val life = launch {
            history.run { history.derivePass { events += "derive ${index.rows.value.count { it.isIndexed }}" } }
        }
        runCurrent()
        // The janitor runs before any backfill. Its failure is logged and the start goes on.
        assertEquals(listOf("janitor 0", "derive 1"), events)

        // A sync adds a record without index columns, so a backfill runs and History derives again.
        waiting("b")
        runCurrent()
        assertEquals(listOf("janitor 0", "derive 1", "derive 2"), events)
        assertFalse(index.rows.value.any { it.awaitsIndex })
        life.cancel()
    }
}
