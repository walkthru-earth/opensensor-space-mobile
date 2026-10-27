package space.opensensor.data.history

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import space.opensensor.archive.ArchiveIndex
import space.opensensor.archive.ArchiveKey
import space.opensensor.archive.ArchiveRecord
import space.opensensor.archive.ArchiveStore
import space.opensensor.archive.PartialMeta
import space.opensensor.archive.PartialWriter
import space.opensensor.data.history.parquet.ByteArrayReader
import space.opensensor.data.history.parquet.ByteReader
import space.opensensor.data.history.parquet.ParquetFile
import space.opensensor.model.TemplateKey
import space.opensensor.net.BlockCache
import space.opensensor.net.MemoryBlockStore
import space.opensensor.storage.StorageEntry
import space.opensensor.storage.StorageJanitor
import space.opensensor.storage.StorageRoots
import space.opensensor.storage.StorageTree
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** The History pass order both phones share, over an in-memory archive and fake platform passes. */
class HistoryOrchestratorTest {
    private val bytes = fixtureBytes("parquet/device/v3-board-2rg-180rows.parquet")
    private val thing = ParquetFile.wrap(bytes).metadata.footer?.thingId ?: "orchestrator-thing"

    private class Store : ArchiveStore {
        val files = HashMap<ArchiveKey, ByteArray>()
        override suspend fun partialLengths(thing: String): Map<String, Long> = emptyMap()
        override suspend fun openPartial(key: ArchiveKey, meta: PartialMeta): PartialWriter = error("read only")
        override suspend fun discardPartial(key: ArchiveKey) = Unit
        override suspend fun exists(key: ArchiveKey): Boolean = key in files
        override fun reader(key: ArchiveKey, partial: Boolean): ByteReader = ByteArrayReader(files.getValue(key))
        override suspend fun commit(key: ArchiveKey) = Unit
        override suspend fun listFinal(thing: String?): List<ArchiveKey> = files.keys.filter { thing == null || it.thing == thing }
    }

    /** An index whose row updates can be held, as a slow backfill would be. */
    private class Index : ArchiveIndex {
        val rows = MutableStateFlow<List<ArchiveRecord>>(emptyList())
        var updating: CompletableDeferred<Unit>? = null
        var releaseUpdate: CompletableDeferred<Unit>? = null

        /** How many next row updates throw, as a busy database would. */
        var failUpdates = 0
        override fun observeAll(): Flow<List<ArchiveRecord>> = rows
        override fun observeThing(thing: String): Flow<List<ArchiveRecord>> = rows.map { list -> list.filter { it.thing == thing } }
        override suspend fun get(key: ArchiveKey): ArchiveRecord? = rows.value.firstOrNull { it.key == key }
        override suspend fun put(record: ArchiveRecord) {
            rows.value = rows.value.filterNot { it.key == record.key } + record
        }
        override suspend fun update(key: ArchiveKey, transform: (ArchiveRecord) -> ArchiveRecord) {
            updating?.complete(Unit)
            releaseUpdate?.await()
            if (failUpdates > 0) {
                failUpdates--
                error("database busy")
            }
            val current = get(key) ?: return
            put(transform(current))
        }
        override suspend fun remove(key: ArchiveKey) {
            rows.value = rows.value.filterNot { it.key == key }
        }
        override suspend fun unindexed(limit: Int): List<ArchiveRecord> = rows.value.filter { !it.isIndexed }.take(limit)
        override suspend fun timeline(thing: String, fromNs: Long, toNs: Long): List<ArchiveRecord> =
            rows.value.filter { it.thing == thing && it.onTimeline(fromNs, toNs) }.sortedWith(ArchiveRecord.TIMELINE_ORDER)
        override suspend fun indexedForThing(thing: String): List<ArchiveRecord> = rows.value.filter { it.thing == thing && it.isIndexed }

        /** A failure for the never indexed flow to throw before its first value, as a closed database would. */
        var neverIndexedFails: Exception? = null
        override fun observeNeverIndexed(): Flow<Boolean> = neverIndexedFails?.let { e -> flow { throw e } } ?: super.observeNeverIndexed()

        /** How many times a time estimate or other caller listed the things. */
        var thingReads = 0
        override suspend fun things(): List<String> = rows.value.map { it.thing }.distinct().also { thingReads++ }
    }

    private val store = Store()
    private val index = Index()

    private suspend fun waiting(part: String): ArchiveRecord {
        val r = ArchiveRecord(thing, "thing=$thing/day/$part.parquet", bytes.size.toLong(), 1, "s", 1, "fake")
        store.files[r.key] = bytes
        index.put(r)
        return r
    }

    private fun TestScope.orchestrator() =
        HistoryOrchestrator(ArchiveIndexRepair(store, index, io = StandardTestDispatcher(testScheduler), nowEpochMs = { 42L }), index)

    /** No storage roots, so the janitor only adopts the finals of [store] that the index lacks. */
    private object NoTree : StorageTree {
        override fun list(dir: String): List<StorageEntry> = emptyList()
        override fun delete(path: String) = Unit
    }

    private fun TestScope.janitor(log: (String) -> Unit) =
        StorageJanitor(StorageRoots(emptyMap()), NoTree, store, index, StandardTestDispatcher(testScheduler), clock = { 42L }, log = log)

    /** One cached feed block over a zero quota, so [BlockCache.enforce] evicts it. */
    private fun TestScope.blocks(onQuota: () -> Unit): Pair<BlockCache, MemoryBlockStore> {
        val blockStore = MemoryBlockStore().apply { write("feed-block", ByteArray(8)) }
        return BlockCache(blockStore, { onQuota(); 0L }, StandardTestDispatcher(testScheduler)) to blockStore
    }

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
    fun aDeriveEntryPointWaitsForABackfillInFlight() = runTest {
        val record = waiting("a")
        val history = orchestrator()
        var sawIndexed: Boolean? = null
        val derive = history.derivePassOf<String> { sawIndexed = index.get(record.key)!!.isIndexed }
        index.updating = CompletableDeferred()
        index.releaseUpdate = CompletableDeferred()

        val backfill = launch { history.backfill() }
        index.updating!!.await()
        val deriving = launch { derive("emptied thing") }
        runCurrent()
        assertEquals(null, sawIndexed, "the entry point waits for the backfill in flight")

        index.releaseUpdate!!.complete(Unit)
        backfill.join()
        deriving.join()
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
    fun runBackfillsThenDerivesAndAgainWhenARecordWaits() = runTest {
        waiting("a")
        val events = ArrayList<String>()
        val history = orchestrator()
        val life = launch {
            history.run { history.derivePass { events += "derive ${index.rows.value.count { it.isIndexed }}" } }
        }
        runCurrent()
        assertEquals(listOf("derive 1"), events)

        // A sync adds a record without index columns, so a backfill runs and History derives again.
        waiting("b")
        runCurrent()
        assertEquals(listOf("derive 1", "derive 2"), events)
        assertFalse(index.rows.value.any { it.awaitsIndex })
        life.cancel()
    }

    @Test
    fun aCleanStartStillBackfillsAndDerivesOnceThenWaitsForARecordThatWaits() = runTest {
        waiting("a")
        val history = orchestrator()
        history.backfill()
        assertFalse(index.rows.value.any { it.awaitsIndex }, "nothing waits when History starts")
        val events = ArrayList<String>()
        val life = launch {
            history.run { history.derivePass { events += "derive ${index.rows.value.count { it.isIndexed }}" } }
        }
        runCurrent()
        assertEquals(listOf("derive 1"), events, "the start pass runs with nothing waiting")

        // An index change that leaves nothing waiting runs no pass.
        index.put(index.rows.value.single().copy(sourceDevice = "fake-b"))
        runCurrent()
        assertEquals(listOf("derive 1"), events)

        waiting("b")
        runCurrent()
        assertEquals(listOf("derive 1", "derive 2"), events)
        life.cancel()
    }

    @Test
    fun anIndexFlowThatFailsBeforeItsFirstValueStillRunsTheStartPass() = runTest {
        waiting("a")
        index.neverIndexedFails = IllegalStateException("database closed")
        val logged = ArrayList<String>()
        val history = HistoryOrchestrator(ArchiveIndexRepair(store, index, io = StandardTestDispatcher(testScheduler), nowEpochMs = { 42L }), index, log = { logged += it })
        val events = ArrayList<String>()

        history.run { history.derivePass { events += "derive ${index.rows.value.count { it.isIndexed }}" } }

        assertEquals(listOf("derive 1"), events, "one start pass, then run returns")
        assertEquals(listOf("index repair stopped: IllegalStateException"), logged)
    }

    @Test
    fun aFailedBackfillRetriesAfterABackoffWithoutANewIndexChange() = runTest {
        waiting("a")
        index.failUpdates = 1
        val derives = ArrayList<Int>()
        val history = orchestrator()
        val life = launch { history.run { history.derivePass { derives += index.rows.value.count { it.isIndexed } } } }
        runCurrent()
        // The index threw, the record still waits, and the index flow will not emit again.
        assertEquals(listOf(0), derives)
        assertTrue(index.rows.value.single().awaitsIndex)

        advanceTimeBy(HistoryOrchestrator.RETRY_DELAYS.first() - 1.seconds)
        runCurrent()
        assertEquals(listOf(0), derives, "no retry before the first backoff")

        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(listOf(0, 1), derives, "the retry backfills and derives again")
        assertFalse(index.rows.value.single().awaitsIndex)
        life.cancel()
    }

    @Test
    fun theStartAndRetryBackfillsWaitForADeriveInFlight() = runTest {
        waiting("a")
        index.failUpdates = 1
        val history = orchestrator()
        val inDerive = CompletableDeferred<Unit>()
        var endDerive = CompletableDeferred<Unit>()
        val held = launch { history.derivePass { inDerive.complete(Unit); endDerive.await() } }
        inDerive.await()

        val life = launch { history.run { history.derivePass { } } }
        runCurrent()
        assertEquals(1, index.failUpdates, "the start backfill waits for the derive in flight")
        endDerive.complete(Unit)
        held.join()
        runCurrent()
        assertEquals(0, index.failUpdates)
        assertTrue(index.rows.value.single().awaitsIndex)

        // A derive holds the pass when the retry falls due, so the retry backfill waits for it.
        endDerive = CompletableDeferred()
        val again = CompletableDeferred<Unit>()
        val heldAgain = launch { history.derivePass { again.complete(Unit); endDerive.await() } }
        again.await()
        advanceTimeBy(HistoryOrchestrator.RETRY_DELAYS.first() + 1.seconds)
        runCurrent()
        assertTrue(index.rows.value.single().awaitsIndex, "the retry backfill waits for the derive in flight")
        endDerive.complete(Unit)
        heldAgain.join()
        runCurrent()
        assertFalse(index.rows.value.single().awaitsIndex)
        life.cancel()
    }

    @Test
    fun retriesGiveUpAfterTheLastBackoff() = runTest {
        waiting("a")
        index.failUpdates = Int.MAX_VALUE
        val derives = ArrayList<Int>()
        val history = orchestrator()
        val life = launch { history.run { history.derivePass { derives += index.rows.value.count { it.isIndexed } } } }
        runCurrent()
        advanceTimeBy(HistoryOrchestrator.RETRY_DELAYS.reduce { a, b -> a + b } + 1.seconds)
        runCurrent()
        assertEquals(1 + HistoryOrchestrator.RETRY_DELAYS.size, derives.size, "one start pass and one per backoff")

        advanceTimeBy((24 * 3600).seconds)
        runCurrent()
        assertEquals(1 + HistoryOrchestrator.RETRY_DELAYS.size, derives.size, "no retry after the last backoff")
        assertTrue(index.rows.value.single().awaitsIndex, "the record waits for the next index change")
        life.cancel()
    }

    @Test
    fun timeEstimatesWaitForADeriveInFlight() = runTest {
        waiting("a")
        val history = orchestrator()
        val inDerive = CompletableDeferred<Unit>()
        val endDerive = CompletableDeferred<Unit>()
        val derive = launch { history.derivePass { inDerive.complete(Unit); endDerive.await() } }
        inDerive.await()

        var estimated = false
        val estimate = launch { history.estimateTimes(); estimated = true }
        runCurrent()
        assertFalse(estimated, "the estimates wait for the derive that reads them")
        endDerive.complete(Unit)
        derive.join()
        estimate.join()
        assertTrue(estimated)
    }

    @Test
    fun aClockReceiptFollowUpEstimatesOnlyInsideAPassThenDerives() = runTest {
        waiting("a")
        val history = orchestrator()
        val inDerive = CompletableDeferred<Unit>()
        val endDerive = CompletableDeferred<Unit>()
        val derive = launch { history.derivePass { inDerive.complete(Unit); endDerive.await() } }
        inDerive.await()

        val events = ArrayList<String>()
        val followUp = launch { history.afterClockReceipt { history.derivePass { events += "derive after ${index.thingReads} estimate" } } }
        runCurrent()
        assertEquals(0, index.thingReads, "the estimates wait for the derive that reads them")
        endDerive.complete(Unit)
        derive.join()
        followUp.join()
        assertEquals(listOf("derive after 1 estimate"), events)
    }

    @Test
    fun startSweepsThenAppliesTheFeedQuotaThenIndexesWhatTheJanitorAdopted() = runTest {
        // A final on disk with no record, as a crash between commit and insert leaves it.
        val orphan = ArchiveRecord(thing, "thing=$thing/day/orphan.parquet", bytes.size.toLong(), 1, "s", 1, "fake")
        store.files[orphan.key] = bytes
        val events = ArrayList<String>()
        val (cache, blockStore) = blocks { events += "feed quota" }
        val history = orchestrator()
        val startup = HistoryStartup(history, janitor { events += "janitor" }, cache)
        assertFalse(startup.started)

        val job = startup.start(this) { history.derivePass { events += "derive ${index.rows.value.count { it.isIndexed }}" } }
        runCurrent()
        assertTrue(startup.started)
        assertEquals(listOf("janitor", "feed quota", "derive 1"), events)
        assertTrue(blockStore.list().isEmpty(), "the feed block quota was applied")
        assertTrue(index.get(orphan.key)!!.isIndexed, "the adopted file is indexed in the same start")

        assertSame(job, startup.start(this) { error("a second start runs nothing") })
        job.cancel()
    }

    @Test
    fun theFeedQuotaAndTheBackfillRunWhenTheJanitorFails() = runTest {
        waiting("a")
        val events = ArrayList<String>()
        val (cache, blockStore) = blocks { events += "feed quota" }
        val history = orchestrator()
        val startup = HistoryStartup(history, janitor { events += "janitor"; error("disk busy") }, cache)

        val job = startup.start(this) { history.derivePass { events += "derive ${index.rows.value.count { it.isIndexed }}" } }
        runCurrent()
        assertEquals(listOf("janitor", "feed quota", "derive 1"), events)
        assertTrue(blockStore.list().isEmpty())
        job.cancel()
    }

    @Test
    fun aNewTemplateDerivesAgainAndAnUnchangedOneDoesNot() = runTest {
        val (cache, _) = blocks { }
        val history = orchestrator()
        val served = ServedTemplates()
        val startup = HistoryStartup(history, janitor { }, cache, templates = FileTemplates.over(served).changes)
        var derives = 0

        val job = startup.start(this) { history.derivePass { derives++ } }
        runCurrent()
        assertEquals(1, derives)

        served.add(coreS3Template.copy(key = TemplateKey("ab".repeat(32), "cd".repeat(32))))
        runCurrent()
        assertEquals(2, derives, "a template the cache loads or a board serves derives again")

        served.add(coreS3Template.copy(key = TemplateKey("ab".repeat(32), "cd".repeat(32))))
        runCurrent()
        assertEquals(2, derives, "a template already held changes nothing")
        job.cancel()
    }
}
