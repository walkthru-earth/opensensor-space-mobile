package earth.walkthru.opensensor.history

import earth.walkthru.aqsync.archive.ArchiveIndex
import earth.walkthru.aqsync.archive.ArchiveKey
import earth.walkthru.aqsync.archive.ArchiveRecord
import earth.walkthru.aqsync.archive.ArchiveStore
import earth.walkthru.aqsync.archive.PartialMeta
import earth.walkthru.aqsync.archive.PartialWriter
import earth.walkthru.aqsync.data.history.DaySpan
import earth.walkthru.aqsync.data.history.HistoryRange
import earth.walkthru.aqsync.data.history.HistorySeriesReader
import earth.walkthru.aqsync.data.history.HistoryTiers
import earth.walkthru.aqsync.data.history.Metrics
import earth.walkthru.aqsync.data.history.Resolution
import earth.walkthru.aqsync.data.history.TierBucket
import earth.walkthru.aqsync.data.history.parquet.ByteReader
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/** The shared History state over a fake archive, without a device or a platform database. */
class HistoryControllerTest {
    private val nowS = 1_790_769_600L // 2026-09-30T12:00Z
    private val todayEndS = 1_790_812_800L // 2026-10-01T00:00Z
    private val lastReadingS = 1_789_909_200L // 2026-09-20T13:00Z
    private val lastDayEndS = 1_789_948_800L // 2026-09-21T00:00Z
    private val day = 86_400L

    private class Store : ArchiveStore {
        override suspend fun partialLengths(station: String): Map<String, Long> = emptyMap()
        override suspend fun openPartial(key: ArchiveKey, meta: PartialMeta): PartialWriter = error("read only")
        override suspend fun discardPartial(key: ArchiveKey) = Unit
        override suspend fun exists(key: ArchiveKey): Boolean = false
        override fun reader(key: ArchiveKey, partial: Boolean): ByteReader = error("no file is read in this test")
        override suspend fun commit(key: ArchiveKey) = Unit
        override suspend fun listFinal(station: String?): List<ArchiveKey> = emptyList()
    }

    private class Index(records: List<ArchiveRecord>) : ArchiveIndex {
        val rows = MutableStateFlow(records)

        /** How often a collector asked for every record. History asks for narrow flows only. */
        var allObserved = 0
        override fun observeAll(): Flow<List<ArchiveRecord>> = rows.also { allObserved++ }
        override fun observeLatestTimed(): Flow<Map<String, Long?>> = rows.map(ArchiveIndex::latestTimedEnds)
        override fun observeStation(station: String): Flow<List<ArchiveRecord>> = rows.map { list -> list.filter { it.station == station } }
        override suspend fun get(key: ArchiveKey): ArchiveRecord? = rows.value.firstOrNull { it.key == key }
        override suspend fun put(record: ArchiveRecord) {
            rows.value = rows.value.filterNot { it.key == record.key } + record
        }
        override suspend fun remove(key: ArchiveKey) {
            rows.value = rows.value.filterNot { it.key == key }
        }
        override suspend fun unindexed(limit: Int): List<ArchiveRecord> = emptyList()

        // No rows on the timeline, so the Day reader never opens a file.
        override suspend fun timeline(station: String, fromNs: Long, toNs: Long): List<ArchiveRecord> = emptyList()
        override suspend fun indexedForStation(station: String): List<ArchiveRecord> = rows.value.filter { it.station == station && it.isIndexed }
        override suspend fun stations(): List<String> = rows.value.map { it.station }.distinct()
    }

    private class Tiers(private val metricIds: Set<String>) : HistoryTiers {
        override fun observe(station: String, metricId: String, tier: Resolution, fromS: Long, toS: Long): Flow<List<TierBucket>> = flowOf(emptyList())
        override fun observeDays(station: String): Flow<DaySpan> = flowOf(DaySpan(1, 1_789_862_400L, 1_789_862_400L))
        override fun observeMetricsWithData(station: String): Flow<Set<String>> = flowOf(metricIds)
    }

    private val clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochSeconds(nowS)
    }

    private fun record(station: String) = ArchiveRecord(
        station = station,
        name = "station=$station/year=2026/month=09/day=20/data_1300.parquet",
        bytes = 10,
        crc32 = 1,
        sha256 = "s",
        syncedAtEpochMs = 1,
        sourceDevice = "fake",
        indexedAtEpochMs = 1,
        utcRows = 10,
        firstUtcNs = (lastReadingS - 3_600) * 1_000_000_000L,
        lastUtcNs = lastReadingS * 1_000_000_000L,
    )

    private fun TestScope.controller(
        metricIds: Set<String> = setOf("ambient_c", "rh"),
        index: Index = Index(listOf(record("s1"))),
        current: String = "s1",
        stations: List<String> = listOf("s1"),
    ): HistoryController {
        val store = Store()
        val tiers = Tiers(metricIds)
        val reader = HistorySeriesReader(store, index, tiers, io = StandardTestDispatcher(testScheduler))
        return HistoryController(
            scope = backgroundScope,
            reader = reader,
            tiers = tiers,
            index = index,
            currentStation = flowOf(current),
            stations = flowOf(stations),
            zone = TimeZone.UTC,
            clock = clock,
            tickMs = 3_600_000,
        )
    }

    @Test
    fun opensOnTheLastDayWithReadingsAndAMetricThatHasData() = runTest {
        val c = controller()
        val s = c.state.first { !it.initializing && it.toS == lastDayEndS && it.metric == Metrics.AMBIENT_TEMP }
        assertEquals("s1", s.station)
        assertEquals(HistoryRange.DAY, s.range)
        assertEquals(todayEndS, s.nowToS)
        assertTrue(s.canPageForward)
        assertEquals(1, s.files.withTime)
        assertEquals(1, s.days.count)
        assertTrue(Metrics.PM25 !in s.availableMetrics)
    }

    @Test
    fun opensOnCorrectedPm25WhenTheArchiveHasIt() = runTest {
        val c = controller(metricIds = setOf("pm25_epa", "pm25", "rh"))
        val s = c.state.first { !it.initializing && it.toS == lastDayEndS }
        assertEquals(Metrics.PM25_CORRECTED, s.metric)
        assertEquals(listOf(Metrics.PM25_CORRECTED, Metrics.PM25), s.availableMetrics.take(2))
    }

    @Test
    fun aCurrentStationWithoutTimedReadingsOpensOnTheOneWithTheLatestWithoutReadingTheWholeIndex() = runTest {
        val untimed = record("s2").copy(utcRows = 0, firstUtcNs = null, lastUtcNs = null)
        val index = Index(listOf(record("s1"), untimed))
        val c = controller(index = index, current = "s2", stations = listOf("s1", "s2"))
        assertEquals("s1", c.state.first { !it.initializing && it.station != null }.station)
        // A timed reading for the current station moves History back to it.
        index.rows.value = listOf(record("s1"), record("s2"))
        assertEquals("s2", c.state.first { it.station == "s2" }.station)
        assertEquals(0, index.allObserved)
    }

    @Test
    fun pagingNeverPassesNowAndOpenDayShowsThatDay() = runTest {
        val c = controller()
        c.state.first { !it.initializing && it.toS == lastDayEndS }
        c.page(1)
        assertEquals(lastDayEndS + day, c.state.first { it.toS == lastDayEndS + day }.toS)
        repeat(20) { c.page(1) }
        val atNow = c.state.first { it.toS == todayEndS }
        assertFalse(atNow.canPageForward)

        c.setRange(HistoryRange.MONTH)
        assertEquals(HistoryRange.MONTH, c.state.first { it.range == HistoryRange.MONTH }.range)
        c.openDay(1_789_862_400L) // 2026-09-20T00:00Z
        val opened = c.state.first { it.range == HistoryRange.DAY && it.toS == lastDayEndS }
        assertTrue(opened.canPageForward)
        assertEquals(nowS, c.nowS())
    }
}
