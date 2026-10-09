package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.archive.ArchiveIndex
import earth.walkthru.aqsync.archive.ArchiveKey
import earth.walkthru.aqsync.archive.ArchiveRecord
import earth.walkthru.aqsync.archive.ArchiveStore
import earth.walkthru.aqsync.archive.PartialMeta
import earth.walkthru.aqsync.archive.PartialWriter
import earth.walkthru.aqsync.data.history.parquet.ByteArrayReader
import earth.walkthru.aqsync.data.history.parquet.ByteReader
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test

/** The shared History sources over a fake archive: index repair, the row reader and the in-memory tiers. */
class HistorySourcesTest {
    private val station = "53315f5f-cb85-4d8d-b623-d56266084189"
    private val dayStart = 1_789_603_200L // 2026-09-17T00:00Z
    private val board = fixtureBytes("parquet/device/v3-board-2rg-105rows.parquet")
    private fun window(range: HistoryRange, toS: Long) = HistoryWindow.endingAt(toS, range, TimeZone.UTC)

    private class Store : ArchiveStore {
        val files = HashMap<ArchiveKey, ByteArray>()
        override suspend fun partialLengths(station: String): Map<String, Long> = emptyMap()
        override suspend fun openPartial(key: ArchiveKey, meta: PartialMeta): PartialWriter = error("read only")
        override suspend fun discardPartial(key: ArchiveKey) = Unit
        override suspend fun exists(key: ArchiveKey): Boolean = key in files
        override fun reader(key: ArchiveKey, partial: Boolean): ByteReader = ByteArrayReader(files[key] ?: throw NoSuchElementException(key.name))
        override suspend fun commit(key: ArchiveKey) = Unit
        override suspend fun listFinal(station: String?): List<ArchiveKey> = files.keys.filter { station == null || it.station == station }
    }

    private class Index : ArchiveIndex {
        val rows = MutableStateFlow<List<ArchiveRecord>>(emptyList())
        override fun observeAll(): Flow<List<ArchiveRecord>> = rows
        override fun observeStation(station: String): Flow<List<ArchiveRecord>> = rows.map { list -> list.filter { it.station == station } }
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

    private fun record(name: String, crc: Long = 1, owner: String = station) =
        ArchiveRecord(station = owner, name = name, bytes = board.size.toLong(), crc32 = crc, sha256 = "s", syncedAtEpochMs = 1, sourceDevice = "fake")

    private suspend fun archive(vararg names: String): Triple<Store, Index, ArchiveIndexRepair> {
        val store = Store()
        val index = Index()
        for ((i, name) in names.withIndex()) {
            val r = record(name, crc = i.toLong())
            store.files[r.key] = board
            index.put(r)
        }
        val repair = ArchiveIndexRepair(store, index, nowEpochMs = { 42L })
        repair.backfillAll()
        return Triple(store, index, repair)
    }

    @Test
    fun repairIndexesEveryVerifiedFileAndMarksForeignAndMissingOnes() = runTest {
        val own = "station=$station/year=2026/month=09/day=17/data_0930_a_2-106-2.parquet"
        val foreign = "station=other/year=2026/month=09/day=17/data_0930_a_2-106-2.parquet"
        val missing = "station=$station/year=2026/month=09/day=17/data_1000.parquet"
        val store = Store()
        val index = Index()
        store.files[record(own).key] = board
        store.files[record(foreign).key] = board
        index.put(record(own))
        index.put(record(foreign))
        index.put(record(missing))
        val repair = ArchiveIndexRepair(store, index, nowEpochMs = { 42L })

        assertEquals(3, repair.backfill())
        val indexed = index.get(record(own).key)!!
        assertTrue(indexed.indexed)
        assertEquals(105L, indexed.rows)
        assertEquals(105L, indexed.utcRows)
        assertTrue(index.get(record(foreign).key)!!.indexError!!.contains("other"))
        assertEquals("file missing from archive", index.get(record(missing).key)!!.indexError)
        // A failed retry of an already failed record writes nothing, so the loop ends.
        assertEquals(0, repair.backfill())
    }

    @Test
    fun dayViewReadsTheRowsOnce() = runTest {
        val (store, index) = archive(
            "station=$station/year=2026/month=09/day=17/data_0930_a_2-106-2.parquet",
            "station=$station/year=2026/month=09/day=17/data_1000_a_107-211-3.parquet",
        )
        val reader = HistorySeriesReader(store, index, ArchiveHistoryTiers(store, index))
        val day = reader.observe(station, Metrics.PM25, HistoryRange.DAY, window(HistoryRange.DAY, dayStart + 86_400)).first()
        assertFalse(day.binned)
        // The two copies share boot and sequence, so each row counts once.
        assertEquals(105, day.points.size)
        assertEquals(105L, day.validRows)
        assertEquals(27.93f, day.mean!!, 0.01f)
        assertEquals(105f / 8640f, day.coverage, 1e-6f)

        val empty = reader.observe(station, Metrics.PM25, HistoryRange.DAY, window(HistoryRange.DAY, dayStart)).first()
        assertTrue(empty.isEmpty)
    }

    @Test
    fun weekViewReadsTheInMemoryTiers() = runTest {
        val (store, index) = archive("station=$station/year=2026/month=09/day=17/data_0930_a_2-106-2.parquet")
        val tiers = ArchiveHistoryTiers(store, index)
        val reader = HistorySeriesReader(store, index, tiers)
        val week = reader.observe(station, Metrics.PM25, HistoryRange.WEEK, window(HistoryRange.WEEK, dayStart + 86_400)).first()
        assertEquals(2, week.points.size)
        assertEquals(27.93f, week.mean!!, 0.01f)
        assertTrue(week.points.all { it.min != null && it.max != null })
        assertEquals(15f / 90f, week.points[0].coverage, 1e-6f)
        assertEquals(1f, week.points[1].coverage, 1e-6f)

        assertEquals(DaySpan(1, dayStart, dayStart), tiers.observeDays(station).first())
        val metrics = tiers.observeMetricsWithData(station).first()
        assertTrue(Metrics.PM25.id in metrics)
        assertTrue(Metrics.BOARD_TEMP.id in metrics)
        assertEquals(DaySpan(), tiers.observeDays("nobody").first())
    }

    @Test
    fun tiersFollowTheIndex() = runTest {
        val store = Store()
        val index = Index()
        val tiers = ArchiveHistoryTiers(store, index)
        assertEquals(0, tiers.observeDays(station).first().count)
        val name = "station=$station/year=2026/month=09/day=17/data_0930_a_2-106-2.parquet"
        store.files[record(name).key] = board
        index.put(record(name))
        // Not indexed yet, so not on the timeline.
        assertEquals(0, tiers.observeDays(station).first().count)
        ArchiveIndexRepair(store, index).backfillAll()
        assertEquals(1, tiers.observeDays(station).first().count)
    }

    @Test
    fun signatureIgnoresOrderAndCarriesTheEstimate() {
        val a = record("a", 1)
        val b = record("b", 2)
        assertEquals(ArchiveHistoryTiers.signature(listOf(a, b)), ArchiveHistoryTiers.signature(listOf(b, a)))
        val est = b.copy(indexedAtEpochMs = 1, rows = 1, utcRows = 0, estFirstUtcNs = 5, estLastUtcNs = 6, estAnchorFile = "a")
        assertTrue(ArchiveHistoryTiers.signature(listOf(a, b)) != ArchiveHistoryTiers.signature(listOf(a, est)))
    }

    @Test
    fun producerStationComesFromTheHivePath() {
        assertEquals("s1", ArchiveIndexRepair.producerStation("station=s1/year=2026/month=09/day=17/data.parquet"))
        assertEquals("s2", ArchiveIndexRepair.producerStation("station=s2/unsynced/boot=ab/data.parquet"))
        assertNull(ArchiveIndexRepair.producerStation("benchmarks/x.parquet"))
    }
}
