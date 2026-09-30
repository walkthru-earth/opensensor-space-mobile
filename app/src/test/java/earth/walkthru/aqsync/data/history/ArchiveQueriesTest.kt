package earth.walkthru.aqsync.data.history

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class ArchiveQueriesTest {
    private val zone = ZoneId.of("UTC")

    @Test
    fun `station metric range and paging changes hide old data before new reads complete`() = runTest {
        val first = ArchiveSeriesQuery("station-a", Metrics.PM25, HistoryRange.DAY, HistoryWindow.endingAt(86_400L, HistoryRange.DAY, zone))
        val queries = MutableStateFlow(first)
        var result: ArchiveQueryResult<ArchiveSeriesQuery, String>? = null
        backgroundScope.launch {
            queries.archiveQueryResults { query -> flow { delay(100); emit(query.station) } }.collect { result = it }
        }
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        assertEquals("station-a", result?.forQuery(first))

        for (next in listOf(first.copy(station = "station-b"), first.copy(metric = Metrics.HUMIDITY), first.copy(range = HistoryRange.WEEK, window = HistoryWindow.endingAt(first.toS, HistoryRange.WEEK, zone)), first.copy(window = HistoryWindow.endingAt(172_800L, HistoryRange.DAY, zone)))) {
            queries.value = next
            // The UI label can update before the result collector is scheduled: old data must be hidden already.
            assertNull(result?.forQuery(next))
            runCurrent()
            assertNull(result?.forQuery(next))
            advanceTimeBy(100)
            runCurrent()
            assertEquals(next.station, result?.forQuery(next))
            assertEquals(next.window.fromS, result?.query?.fromS)
        }
    }

    @Test
    fun `midnight and station rollover cancel old daily reads without relabeling summaries`() = runTest {
        val first = ArchiveDayQuery("old-station", 0)
        val queries = MutableStateFlow(first)
        val results = mutableListOf<ArchiveQueryResult<ArchiveDayQuery, String>>()
        var oldReadCancelled = false
        backgroundScope.launch {
            queries.archiveQueryResults { query ->
                flow {
                    try {
                        delay(1_000)
                        emit("${query.station}:${query.dayStartS}")
                    } finally {
                        if (query == first) oldReadCancelled = true
                    }
                }
            }.collect { results += it }
        }
        runCurrent()
        advanceTimeBy(500)
        val next = ArchiveDayQuery("new-station", 86_400)
        queries.value = next
        runCurrent()
        assertTrue(oldReadCancelled)
        assertNull(results.last().forQuery(next))
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("new-station:86400", results.last().forQuery(next))
        assertTrue(results.none { it.value == "old-station:0" })
        assertNull(results.last().forQuery(next.copy(dayStartS = 172_800)))
    }
}
