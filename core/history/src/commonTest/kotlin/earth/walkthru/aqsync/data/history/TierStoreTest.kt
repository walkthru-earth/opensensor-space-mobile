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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
        override suspend fun partialLengths(station: String): Map<String, Long> = emptyMap()
        override suspend fun openPartial(key: ArchiveKey, meta: PartialMeta): PartialWriter = error("read only")
        override suspend fun discardPartial(key: ArchiveKey) = Unit
        override suspend fun exists(key: ArchiveKey): Boolean = key in files
        override fun reader(key: ArchiveKey, partial: Boolean): ByteReader {
            reads++
            return ByteArrayReader(files[key] ?: throw NoSuchElementException(key.name))
        }
        override suspend fun commit(key: ArchiveKey) = Unit
        override suspend fun listFinal(station: String?): List<ArchiveKey> = files.keys.filter { station == null || it.station == station }
    }

    private class Index : ArchiveIndex {
        val rows = MutableStateFlow<List<ArchiveRecord>>(emptyList())
        var stationObservers = 0
        override fun observeAll(): Flow<List<ArchiveRecord>> = rows
        override fun observeStation(station: String): Flow<List<ArchiveRecord>> =
            rows.map { list -> list.filter { it.station == station } }.onStart { stationObservers++ }
        override suspend fun get(key: ArchiveKey): ArchiveRecord? = rows.value.firstOrNull { it.key == key }
        override suspend fun put(record: ArchiveRecord) {
            rows.value = rows.value.filterNot { it.key == record.key } + record
        }
        override suspend fun remove(key: ArchiveKey) {
            rows.value = rows.value.filterNot { it.key == key }
        }
        override suspend fun unindexed(limit: Int): List<ArchiveRecord> = rows.value.filter { it.indexedAtEpochMs == null || it.indexError != null }.take(limit)
        override suspend fun timeline(station: String, fromNs: Long, toNs: Long): List<ArchiveRecord> =
            rows.value.filter { it.station == station && it.onTimeline(fromNs, toNs) }.sortedWith(ArchiveRecord.TIMELINE_ORDER)
        override suspend fun indexedForStation(station: String): List<ArchiveRecord> = rows.value.filter { it.station == station && it.indexed }
        override suspend fun stations(): List<String> = rows.value.map { it.station }.distinct()
    }

    /** A [TierStore] in memory that counts its traffic, standing in for the files a new process finds. */
    private class MemoryTierStore : TierStore {
        val saved = HashMap<Pair<String, Long>, ByteArray>()
        var loads = 0
        var saves = 0
        override suspend fun load(station: String, epochDay: Long, signature: String): DayTiers? {
            loads++
            return saved[station to epochDay]?.let { TierCodec.decode(it, signature) }
        }
        override suspend fun save(station: String, epochDay: Long, signature: String, tiers: DayTiers) {
            saves++
            saved[station to epochDay] = TierCodec.encode(signature, tiers)
        }
        override suspend fun retain(station: String, days: Set<Long>) {
            saved.keys.removeAll { it.first == station && it.second !in days }
        }
    }

    private val sample: DayTiers = mapOf(
        Resolution.MIN_15 to mapOf(
            Metrics.PM25 to listOf(TierBucket(1_728_000_000, 4, 3, 1, 12.5f, 1f, Float.NaN), TierBucket(1_728_000_900, 0, 0, 0, Float.NaN, Float.NaN, Float.NaN)),
            Metrics.BOARD_TEMP to emptyList(),
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
        val station = ParquetFile.wrap(bytes).metadata.stationId ?: "tier-station"
        val store = Store()
        val index = Index()
        val r = ArchiveRecord(station, "station=$station/day/part.parquet", bytes.size.toLong(), 1, "s", 1, "fake")
        store.files[r.key] = bytes
        index.put(r)
        ArchiveIndexRepair(store, index, nowEpochMs = { 42L }).backfillAll()
        return Triple(store, index, station)
    }

    @Test
    fun aNewProcessReadsSavedTiersWithoutDecodingParquet() = runTest {
        val (store, index, station) = archive()
        val tierStore = MemoryTierStore()
        val first = ArchiveHistoryTiers(store, index, tierStore = tierStore)
        val expected = first.observe(station, Metrics.PM25, Resolution.MIN_15, 0, Long.MAX_VALUE).first()
        assertTrue(expected.isNotEmpty())
        assertTrue(first.decodedDays > 0)
        assertEquals(first.decodedDays, tierStore.saves)

        store.reads = 0
        val second = ArchiveHistoryTiers(store, index, tierStore = tierStore)
        val actual = second.observe(station, Metrics.PM25, Resolution.MIN_15, 0, Long.MAX_VALUE).first()
        assertEquals(0, second.decodedDays)
        assertEquals(0, store.reads)
        assertEquals(expected.map { it.startEpochS to it.validRows }, actual.map { it.startEpochS to it.validRows })
        assertEquals(expected.map { it.mean.toRawBits() }, actual.map { it.mean.toRawBits() })
    }

    @Test
    fun aDayThatLostItsSourcesLeavesTheStore() = runTest {
        val (store, index, station) = archive()
        val tierStore = MemoryTierStore()
        val tiers = ArchiveHistoryTiers(store, index, tierStore = tierStore)
        tiers.observeDays(station).first()
        assertTrue(tierStore.saved.isNotEmpty())
        index.rows.value = emptyList()
        tiers.observeDays(station).first()
        assertTrue(tierStore.saved.isEmpty())
    }

    @Test
    fun theStationsObserversShareOneIndexCollection() = runTest {
        val (store, index, station) = archive()
        val tiers = ArchiveHistoryTiers(store, index, io = StandardTestDispatcher(testScheduler), shareScope = backgroundScope)
        index.stationObservers = 0
        val jobs = listOf(
            backgroundScope.launch { tiers.observe(station, Metrics.PM25, Resolution.MIN_15, 0, Long.MAX_VALUE).collect {} },
            backgroundScope.launch { tiers.observeDays(station).collect {} },
            backgroundScope.launch { tiers.observeMetricsWithData(station).collect {} },
        )
        runCurrent()
        assertEquals(1, index.stationObservers)
        assertTrue(tiers.decodedDays > 0)
        jobs.forEach { it.cancel() }
    }
}
