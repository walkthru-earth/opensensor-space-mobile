package space.opensensor.history

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import space.opensensor.archive.ArchiveIndex
import space.opensensor.archive.ArchiveKey
import space.opensensor.archive.ArchiveRecord
import space.opensensor.archive.ArchiveStore
import space.opensensor.archive.PartialMeta
import space.opensensor.archive.PartialWriter
import space.opensensor.data.history.AirMetrics
import space.opensensor.data.history.DaySpan
import space.opensensor.data.history.HistoryRange
import space.opensensor.data.history.HistorySeriesReader
import space.opensensor.data.history.HistoryTiers
import space.opensensor.data.history.Resolution
import space.opensensor.data.history.TierBucket
import space.opensensor.data.history.parquet.ByteReader
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
        override suspend fun partialLengths(thing: String): Map<String, Long> = emptyMap()
        override suspend fun openPartial(key: ArchiveKey, meta: PartialMeta): PartialWriter = error("read only")
        override suspend fun discardPartial(key: ArchiveKey) = Unit
        override suspend fun exists(key: ArchiveKey): Boolean = false
        override fun reader(key: ArchiveKey, partial: Boolean): ByteReader = error("no file is read in this test")
        override suspend fun commit(key: ArchiveKey) = Unit
        override suspend fun listFinal(thing: String?): List<ArchiveKey> = emptyList()
    }

    private class Index(records: List<ArchiveRecord>) : ArchiveIndex {
        val rows = MutableStateFlow(records)

        /** How often a collector asked for every record. History asks for narrow flows only. */
        var allObserved = 0
        override fun observeAll(): Flow<List<ArchiveRecord>> = rows.also { allObserved++ }
        override fun observeLatestTimed(): Flow<Map<String, Long?>> = rows.map(ArchiveIndex::latestTimedEnds)
        override fun observeThing(thing: String): Flow<List<ArchiveRecord>> = rows.map { list -> list.filter { it.thing == thing } }
        override suspend fun get(key: ArchiveKey): ArchiveRecord? = rows.value.firstOrNull { it.key == key }
        override suspend fun put(record: ArchiveRecord) {
            rows.value = rows.value.filterNot { it.key == record.key } + record
        }
        override suspend fun remove(key: ArchiveKey) {
            rows.value = rows.value.filterNot { it.key == key }
        }
        override suspend fun unindexed(limit: Int): List<ArchiveRecord> = emptyList()

        // No rows on the timeline, so the Day reader never opens a file.
        override suspend fun timeline(thing: String, fromNs: Long, toNs: Long): List<ArchiveRecord> = emptyList()
        override suspend fun indexedForThing(thing: String): List<ArchiveRecord> = rows.value.filter { it.thing == thing && it.isIndexed }
        override suspend fun things(): List<String> = rows.value.map { it.thing }.distinct()
    }

    private class Tiers(private val metricIds: Set<String>) : HistoryTiers {
        override fun observe(thing: String, metricId: String, tier: Resolution, fromS: Long, toS: Long): Flow<List<TierBucket>> = flowOf(emptyList())
        override fun observeDays(thing: String): Flow<DaySpan> = flowOf(DaySpan(1, 1_789_862_400L, 1_789_862_400L))
        override fun observeMetricsWithData(thing: String): Flow<Set<String>> = flowOf(metricIds)
    }

    private val clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochSeconds(nowS)
    }

    private fun record(thing: String) = ArchiveRecord(
        thing = thing,
        name = "thing=$thing/year=2026/month=09/day=20/data_1300.parquet",
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
        metricIds: Set<String> = setOf(AirMetrics.AMBIENT_TEMP.id, AirMetrics.HUMIDITY.id),
        index: Index = Index(listOf(record("s1"))),
        current: String = "s1",
        things: List<String> = listOf("s1"),
    ): HistoryController {
        val store = Store()
        val tiers = Tiers(metricIds)
        val reader = HistorySeriesReader(store, index, tiers, io = StandardTestDispatcher(testScheduler))
        return HistoryController(
            scope = backgroundScope,
            reader = reader,
            tiers = tiers,
            index = index,
            currentThing = flowOf(current),
            things = flowOf(things),
            zone = TimeZone.UTC,
            clock = clock,
            tickMs = 3_600_000,
        )
    }

    @Test
    fun opensOnTheLastDayWithReadingsAndAMetricThatHasData() = runTest {
        val c = controller()
        val s = c.state.first { !it.initializing && it.toS == lastDayEndS && it.metric == AirMetrics.AMBIENT_TEMP }
        assertEquals("s1", s.thing)
        assertEquals(HistoryRange.DAY, s.range)
        assertEquals(todayEndS, s.nowToS)
        assertTrue(s.canPageForward)
        assertEquals(1, s.files.withTime)
        assertEquals(1, s.days.count)
        assertTrue(AirMetrics.PM25 !in s.availableMetrics)
    }

    @Test
    fun opensOnCorrectedPm25WhenTheArchiveHasIt() = runTest {
        val c = controller(metricIds = setOf(AirMetrics.PM25_CORRECTED.id, AirMetrics.PM25.id, AirMetrics.HUMIDITY.id))
        val s = c.state.first { !it.initializing && it.toS == lastDayEndS }
        assertEquals(AirMetrics.PM25_CORRECTED, s.metric)
        assertEquals(listOf(AirMetrics.PM25_CORRECTED, AirMetrics.PM25), s.availableMetrics.take(2))
    }

    @Test
    fun aCurrentThingWithoutTimedReadingsOpensOnTheOneWithTheLatestWithoutReadingTheWholeIndex() = runTest {
        val untimed = record("s2").copy(utcRows = 0, firstUtcNs = null, lastUtcNs = null)
        val index = Index(listOf(record("s1"), untimed))
        val c = controller(index = index, current = "s2", things = listOf("s1", "s2"))
        assertEquals("s1", c.state.first { !it.initializing && it.thing != null }.thing)
        // A timed reading for the current thing moves History back to it.
        index.rows.value = listOf(record("s1"), record("s2"))
        assertEquals("s2", c.state.first { it.thing == "s2" }.thing)
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
