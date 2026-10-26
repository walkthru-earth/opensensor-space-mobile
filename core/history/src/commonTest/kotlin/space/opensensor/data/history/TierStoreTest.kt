package space.opensensor.data.history

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
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
import space.opensensor.storage.MemoryPressure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** #63. Saved tiers decode back exactly, refuse damage, and spare a new process every Parquet decode. */
class TierStoreTest {
    private class Store : ArchiveStore {
        val files = HashMap<ArchiveKey, ByteArray>()
        var reads = 0
        var failures = 0
        override suspend fun partialLengths(thing: String): Map<String, Long> = emptyMap()
        override suspend fun openPartial(key: ArchiveKey, meta: PartialMeta): PartialWriter = error("read only")
        override suspend fun discardPartial(key: ArchiveKey) = Unit
        override suspend fun exists(key: ArchiveKey): Boolean = key in files
        override fun reader(key: ArchiveKey, partial: Boolean): ByteReader {
            reads++
            if (failures > 0) {
                failures--
                throw IllegalStateException("temporarily unavailable")
            }
            return ByteArrayReader(files[key] ?: throw NoSuchElementException(key.name))
        }
        override suspend fun commit(key: ArchiveKey) = Unit
        override suspend fun listFinal(thing: String?): List<ArchiveKey> = files.keys.filter { thing == null || it.thing == thing }
    }

    private class Index : ArchiveIndex {
        val rows = MutableStateFlow<List<ArchiveRecord>>(emptyList())
        var thingObservers = 0
        override fun observeAll(): Flow<List<ArchiveRecord>> = rows
        override fun observeThing(thing: String): Flow<List<ArchiveRecord>> =
            rows.map { list -> list.filter { it.thing == thing } }.onStart { thingObservers++ }
        override suspend fun get(key: ArchiveKey): ArchiveRecord? = rows.value.firstOrNull { it.key == key }
        override suspend fun put(record: ArchiveRecord) {
            rows.value = rows.value.filterNot { it.key == record.key } + record
        }
        override suspend fun remove(key: ArchiveKey) {
            rows.value = rows.value.filterNot { it.key == key }
        }
        override suspend fun unindexed(limit: Int): List<ArchiveRecord> = rows.value.filter { !it.isIndexed }.take(limit)
        override suspend fun timeline(thing: String, fromNs: Long, toNs: Long): List<ArchiveRecord> =
            rows.value.filter { it.thing == thing && it.onTimeline(fromNs, toNs) }.sortedWith(ArchiveRecord.TIMELINE_ORDER)
        override suspend fun indexedForThing(thing: String): List<ArchiveRecord> = rows.value.filter { it.thing == thing && it.isIndexed }
        override suspend fun things(): List<String> = rows.value.map { it.thing }.distinct()
    }

    /** A [TierStore] in memory that counts its traffic, standing in for the files a new process finds. */
    private class MemoryTierStore : TierStore {
        val saved = HashMap<Pair<String, Long>, ByteArray>()
        var loads = 0
        var saves = 0
        override suspend fun load(thing: String, epochDay: Long, signature: String): DayTiers? {
            loads++
            return saved[thing to epochDay]?.let { TierCodec.decode(it, signature) }
        }
        override suspend fun save(thing: String, epochDay: Long, signature: String, tiers: DayTiers) {
            saves++
            saved[thing to epochDay] = TierCodec.encode(signature, tiers)
        }
        override suspend fun retain(thing: String, days: Set<Long>) {
            saved.keys.removeAll { it.first == thing && it.second !in days }
        }
        override suspend fun savedDays(thing: String): Set<Long> = saved.keys.filter { it.first == thing }.mapTo(HashSet()) { it.second }
        override suspend fun loadSaved(thing: String, epochDay: Long): SavedDay? = saved[thing to epochDay]?.let(TierCodec::decodeSaved)
    }

    private val sample: DayTiers = mapOf(
        Resolution.MIN15 to mapOf(
            Metrics.PM25.id to listOf(TierBucket(1_728_000_000, 4, 3, 1, 12.5f, 1f, Float.NaN), TierBucket(1_728_000_900, 0, 0, 0, Float.NaN, Float.NaN, Float.NaN)),
            Metrics.BOARD_TEMP.id to emptyList(),
        ),
        Resolution.DAY to emptyMap(),
    )

    @Test
    fun aSavedDayDecodesToTheSameBuckets() {
        val bytes = TierCodec.encode("sig", sample)
        val back = assertNotNull(TierCodec.decode(bytes, "sig"))
        assertEquals(sample.keys, back.keys)
        for ((resolution, perMetric) in sample) {
            assertEquals(perMetric.keys, back.getValue(resolution).keys)
            for ((metric, buckets) in perMetric) {
                // Compare raw bits so a NaN mean still counts as equal.
                fun bits(list: List<TierBucket>) = list.map { listOf(it.startEpochS, it.rows, it.validRows, it.estimatedRows, it.mean.toRawBits(), it.min.toRawBits(), it.max.toRawBits()) }
                assertEquals(bits(buckets), bits(back.getValue(resolution).getValue(metric)))
            }
        }
    }

    @Test
    fun tiersSavedByAnOlderVersionAreRebuilt() = runTest {
        // Version 4 keys metrics by template column and keeps each bucket's sample interval (#186).
        // Saved days must rebuild to gain them.
        assertEquals(4, TierCodec.VERSION)
        val (store, index, thing) = archive()
        val tierStore = MemoryTierStore()
        ArchiveHistoryTiers(store, index, tierStore = tierStore, templates = CoreS3Files).observeDays(thing).first()
        assertTrue(tierStore.saved.isNotEmpty())
        // Rewrite each saved day's leading version, little endian, to the previous one.
        for (bytes in tierStore.saved.values) {
            val old = TierCodec.VERSION - 1
            for (i in 0 until 4) bytes[i] = (old ushr (8 * i)).toByte()
            assertNull(TierCodec.decodeSaved(bytes))
        }
        val saves = tierStore.saves
        val restarted = ArchiveHistoryTiers(store, index, tierStore = tierStore, templates = CoreS3Files)
        assertTrue(restarted.observeDays(thing).first().count > 0)
        assertTrue(restarted.decodedDays > 0, "an old saved day is rebuilt from the archive")
        assertTrue(tierStore.saves > saves)
        for (bytes in tierStore.saved.values) assertNotNull(TierCodec.decodeSaved(bytes))
    }

    @Test
    fun anotherSignatureOrDamagedBytesGiveNothing() {
        val bytes = TierCodec.encode("sig", sample)
        assertNull(TierCodec.decode(bytes, "other"))
        assertNull(TierCodec.decode(bytes.copyOf(bytes.size - 1), "sig"))
        assertNull(TierCodec.decode(bytes + 0, "sig"))
        assertNull(TierCodec.decode(ByteArray(0), "sig"))
        // A count far beyond the bytes left is refused before any list is sized from it.
        val huge = bytes.copyOf().also { b -> for (i in 0 until 4) b[4 + 4 + 3 + 4 + 4 + i] = 0x7F }
        assertNull(TierCodec.decode(huge, "sig"))
        // Every truncation is refused, never thrown.
        for (n in bytes.indices) assertNull(TierCodec.decode(bytes.copyOf(n), "sig"), "truncated to $n")
    }

    private suspend fun archive(): Triple<Store, Index, String> {
        val bytes = fixtureBytes("parquet/device/v3-board-2rg-180rows.parquet")
        val thing = ParquetFile.wrap(bytes).metadata.footer?.thingId ?: "tier-thing"
        val store = Store()
        val index = Index()
        val r = ArchiveRecord(thing, "thing=$thing/day/part.parquet", bytes.size.toLong(), 1, "s", 1, "fake")
        store.files[r.key] = bytes
        index.put(r)
        ArchiveIndexRepair(store, index, nowEpochMs = { 42L }, templates = CoreS3Files).backfillAll()
        return Triple(store, index, thing)
    }

    @Test
    fun aNewProcessReadsSavedTiersWithoutDecodingParquet() = runTest {
        val (store, index, thing) = archive()
        val tierStore = MemoryTierStore()
        val first = ArchiveHistoryTiers(store, index, tierStore = tierStore, templates = CoreS3Files)
        val expected = first.observe(thing, Metrics.PM25.id, Resolution.MIN15, 0, Long.MAX_VALUE).first()
        assertTrue(expected.isNotEmpty())
        assertTrue(first.decodedDays > 0)
        assertEquals(first.decodedDays, tierStore.saves)

        store.reads = 0
        val second = ArchiveHistoryTiers(store, index, tierStore = tierStore, templates = CoreS3Files)
        val actual = second.observe(thing, Metrics.PM25.id, Resolution.MIN15, 0, Long.MAX_VALUE).first()
        assertEquals(0, second.decodedDays)
        assertEquals(0, store.reads)
        assertEquals(expected.map { it.startEpochS to it.validRows }, actual.map { it.startEpochS to it.validRows })
        assertEquals(expected.map { it.mean.toRawBits() }, actual.map { it.mean.toRawBits() })
    }

    @Test
    fun aDayThatLostItsSourcesLeavesTheStore() = runTest {
        val (store, index, thing) = archive()
        val tierStore = MemoryTierStore()
        val tiers = ArchiveHistoryTiers(store, index, tierStore = tierStore, templates = CoreS3Files)
        tiers.observeDays(thing).first()
        assertTrue(tierStore.saved.isNotEmpty())
        index.rows.value = emptyList()
        tiers.observeDays(thing).first()
        assertTrue(tierStore.saved.isEmpty())
    }

    @Test
    fun aDayAWaitingRecordHoldsStaysInTheStore() = runTest {
        val (store, index, thing) = archive()
        val tierStore = MemoryTierStore()
        val tiers = ArchiveHistoryTiers(store, index, tierStore = tierStore, templates = CoreS3Files)
        tiers.observeDays(thing).first()
        val saved = tierStore.saved.keys.toSet()
        assertTrue(saved.isNotEmpty())
        val record = index.rows.value.single()

        // Reopened by an index migration, the record keeps its span and holds those days (#87).
        index.rows.value = listOf(record.copy(indexedAtEpochMs = null))
        tiers.observeDays(thing).first()
        assertEquals(saved, tierStore.saved.keys)

        // Without a span it could land on any day, so every day stays.
        index.rows.value = listOf(record.copy(indexedAtEpochMs = null, utcRows = 0, firstUtcNs = null, lastUtcNs = null))
        tiers.observeDays(thing).first()
        assertEquals(saved, tierStore.saved.keys)

        // Indexed again, a new process reads the held tiers back instead of decoding the file.
        index.rows.value = listOf(record)
        val restarted = ArchiveHistoryTiers(store, index, tierStore = tierStore, templates = CoreS3Files)
        assertEquals(saved.size, restarted.observeDays(thing).first().count)
        assertEquals(0, restarted.decodedDays)
    }

    @Test
    fun aDayAWaitingRecordHoldsStaysVisibleInHistoryUntilItIndexesAgain() = runTest {
        val (store, index, thing) = archive()
        val tierStore = MemoryTierStore()
        val tiers = ArchiveHistoryTiers(store, index, tierStore = tierStore, templates = CoreS3Files)
        val span = tiers.observeDays(thing).first()
        val buckets = tiers.observe(thing, Metrics.PM25.id, Resolution.MIN15, 0, Long.MAX_VALUE).first()
        val metrics = tiers.observeMetricsWithData(thing).first()
        assertTrue(span.count > 0)
        assertTrue(buckets.isNotEmpty())
        val record = index.rows.value.single()

        // A backfill reopened the record. Its days stay in History as they were, on Android and iOS alike.
        index.rows.value = listOf(record.copy(indexedAtEpochMs = null))
        assertEquals(span, tiers.observeDays(thing).first())
        assertEquals(buckets, tiers.observe(thing, Metrics.PM25.id, Resolution.MIN15, 0, Long.MAX_VALUE).first())
        assertEquals(metrics, tiers.observeMetricsWithData(thing).first())

        // A record with no span holds every day, and a new process shows them from the store alone.
        index.rows.value = listOf(record.copy(indexedAtEpochMs = null, utcRows = 0, firstUtcNs = null, lastUtcNs = null))
        val restarted = ArchiveHistoryTiers(store, index, tierStore = tierStore, templates = CoreS3Files)
        assertEquals(span, restarted.observeDays(thing).first())
        assertEquals(buckets, restarted.observe(thing, Metrics.PM25.id, Resolution.MIN15, 0, Long.MAX_VALUE).first())
        assertEquals(0, restarted.decodedDays)

        // Once nothing holds them, days without sources leave History.
        index.rows.value = emptyList()
        assertEquals(0, restarted.observeDays(thing).first().count)
    }

    @Test
    fun theThingsObserversShareOneIndexCollection() = runTest {
        val (store, index, thing) = archive()
        val tiers = ArchiveHistoryTiers(store, index, io = StandardTestDispatcher(testScheduler), shareScope = backgroundScope, templates = CoreS3Files)
        index.thingObservers = 0
        val jobs = listOf(
            backgroundScope.launch { tiers.observe(thing, Metrics.PM25.id, Resolution.MIN15, 0, Long.MAX_VALUE).collect {} },
            backgroundScope.launch { tiers.observeDays(thing).collect {} },
            backgroundScope.launch { tiers.observeMetricsWithData(thing).collect {} },
        )
        runCurrent()
        assertEquals(1, index.thingObservers)
        assertTrue(tiers.decodedDays > 0)
        jobs.forEach { it.cancel() }
    }

    @Test
    fun sharedReplayDoesNotKeepBucketsAfterMemoryTrim() = runTest {
        val (store, index, thing) = archive()
        val tierStore = MemoryTierStore()
        val tiers = ArchiveHistoryTiers(store, index, io = StandardTestDispatcher(testScheduler), tierStore = tierStore, shareScope = backgroundScope, templates = CoreS3Files)
        val summary = backgroundScope.launch { tiers.observeDays(thing).collect {} }
        runCurrent()
        val expected = tiers.observe(thing, Metrics.PM25.id, Resolution.MIN15, 0, Long.MAX_VALUE).first()
        assertTrue(expected.isNotEmpty())
        tiers.trim(MemoryPressure.Level.Critical)
        assertEquals(0, tiers.cachedDays())
        val loads = tierStore.loads
        val decodes = tiers.decodedDays
        val actual = tiers.observe(thing, Metrics.PM25.id, Resolution.MIN15, 0, Long.MAX_VALUE).first()
        assertEquals(expected, actual)
        assertTrue(tierStore.loads > loads, "replayed summaries reload released buckets from disk")
        assertEquals(decodes, tiers.decodedDays)
        summary.cancel()
    }

    @Test
    fun idleSharedReplayExpiresBeforeReturningToHistory() = runTest {
        val (store, index, thing) = archive()
        val tiers = ArchiveHistoryTiers(store, index, io = StandardTestDispatcher(testScheduler), shareScope = backgroundScope, templates = CoreS3Files)
        assertTrue(tiers.observeDays(thing).first().count > 0)
        runCurrent()
        advanceTimeBy(5_001)
        runCurrent()
        index.rows.value = emptyList()
        assertEquals(0, tiers.observeDays(thing).first().count)
    }

    @Test
    fun failedSourcesDoNotPersistPartialTiersAcrossRestart() = runTest {
        val (store, index, thing) = archive()
        val tierStore = MemoryTierStore()
        val first = ArchiveHistoryTiers(store, index, tierStore = tierStore, templates = CoreS3Files)
        store.failures = 1
        assertEquals(0, first.observeDays(thing).first().count)
        assertEquals(0, first.cachedDays())
        assertEquals(0, tierStore.saves)
        assertTrue(tierStore.saved.isEmpty())
        val restarted = ArchiveHistoryTiers(store, index, tierStore = tierStore, templates = CoreS3Files)
        assertTrue(restarted.observeDays(thing).first().count > 0)
        assertTrue(restarted.decodedDays > 0)
        assertTrue(tierStore.saves > 0)
    }
}
