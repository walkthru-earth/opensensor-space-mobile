package earth.walkthru.opensensor.today

import earth.walkthru.aqsync.data.history.HistoryRange
import earth.walkthru.aqsync.data.history.HistorySeries
import earth.walkthru.aqsync.data.history.HistoryWindow
import earth.walkthru.aqsync.data.history.MetricSpec
import earth.walkthru.aqsync.data.history.TodaySoFar
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TodayFormatTest {
    @Test
    fun readingsDropATrailingZeroAndMissingStaysMissing() {
        assertEquals("21", fmt(21.0))
        assertEquals("21.5", fmt(21.46))
        assertEquals("1000.0", fmt(1000.0))
        assertEquals(NO_VALUE, fmt(null))
    }

    @Test
    fun bytesUseBinaryUnits() {
        assertEquals("512 B", formatBytes(512))
        assertEquals("1.5 KiB", formatBytes(1536))
        assertEquals("2.00 MiB", formatBytes(2L * 1024 * 1024))
        assertEquals("1.00 GiB", formatBytes(1024L * 1024 * 1024))
    }

    @Test
    fun uptimeKeepsTheTwoLargestUnits() {
        assertEquals("1m 5s", formatUptime(65))
        assertEquals("2h 3m", formatUptime(2 * 3600 + 3 * 60))
        assertEquals("1d 4h", formatUptime(86_400 + 4 * 3600))
    }

    @Test
    fun agoPicksTheLargestWholeUnit() {
        assertEquals(AgoUnit.JUST_NOW to 0L, agoParts(-5))
        assertEquals(AgoUnit.SECONDS to 12L, agoParts(12_400))
        assertEquals(AgoUnit.MINUTES to 2L, agoParts(150_000))
        assertEquals(AgoUnit.HOURS to 3L, agoParts(3 * 3_600_000L + 1))
        assertEquals(AgoUnit.DAYS to 2L, agoParts(2 * 86_400_000L))
    }

    @Test
    fun fillReplacesEitherPlaceholder() {
        assertEquals("12 s ago", fill("%1\$s s ago", "12"))
        assertEquals("قبل 12 ث", fill("قبل %1\$d ث", "12"))
    }

    @Test
    fun theFirmwareClockPrintsAsAnInstant() {
        assertEquals("2026-01-01T00:00:00Z", isoInstant(1_767_225_600L * 1_000_000_000L))
    }

    @Test
    fun todaySoFarComesFromTheHourlyDayAndIsNullWithoutAStation() = runTest {
        val zone = TimeZone.UTC
        val station = MutableStateFlow<String?>(null)
        val seen = mutableListOf<String>()
        val observe = { id: String, metric: MetricSpec, window: HistoryWindow ->
            seen += id
            flowOf(
                HistorySeries(metric, HistoryRange.DAY, window.fromS, window.toS, emptyList(), validRows = 30, expectedRows = 60, mean = 12.5f, max = 20f, binned = false),
            ) as Flow<HistorySeries>
        }
        assertNull(todaySoFar(station, observe, zone, flowOf(1_767_225_600L)).take(1).toList().single())

        station.value = "st-1"
        val value = todaySoFar(station, observe, zone, flowOf(1_767_225_600L)).filterNotNull().first()
        assertEquals(TodaySoFar(12.5f, 30, 0.5f), value)
        assertEquals(listOf("st-1"), seen)
    }
}
