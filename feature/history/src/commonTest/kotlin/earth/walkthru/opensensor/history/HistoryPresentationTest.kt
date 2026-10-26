package earth.walkthru.opensensor.history

import earth.walkthru.aqsync.archive.ArchiveIndex
import earth.walkthru.aqsync.archive.ArchiveRecord
import earth.walkthru.aqsync.data.history.Metrics
import earth.walkthru.aqsync.data.history.startOfDay
import earth.walkthru.aqsync.settings.DetailLevel
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HistoryPresentationTest {
    private fun file(station: String, producer: String, dated: Boolean, indexed: Boolean = true, lastUtcNs: Long = 1_800_000_000_000_000_000): ArchiveRecord = ArchiveRecord(
        station = station,
        name = if (dated) "station=$producer/year=2026/month=09/day=29/sample.parquet" else "station=$producer/unsynced/boot=one/sample.parquet",
        bytes = 10,
        crc32 = 1,
        sha256 = "hash",
        syncedAtEpochMs = 1,
        sourceDevice = "device",
        indexedAtEpochMs = if (indexed) 1 else null,
        indexError = if (producer != station) "File belongs to station $producer" else null,
        utcRows = if (dated && producer == station) 1 else 0,
        lastUtcNs = if (dated && producer == station) lastUtcNs else null,
    )

    @Test
    fun earlierTimedStationOpensByDefaultButAnExplicitCurrentSelectionStays() {
        val earlier = file("earlier", "earlier", dated = true)
        val current = file("current", "current", dated = false)
        assertEquals("earlier", preferredHistoryStation("current", listOf("earlier", "current"), ArchiveIndex.latestTimedEnds(listOf(earlier, current)), null))
        assertEquals("current", preferredHistoryStation("current", listOf("earlier", "current"), ArchiveIndex.latestTimedEnds(listOf(earlier, current)), "current"))
    }

    @Test
    fun aStationWhoseTimedFilesHaveNoEndRanksBelowAnyRealEnd() {
        val latest = mapOf<String, Long?>("noEnd" to null, "lowEnd" to Long.MIN_VALUE)
        assertEquals("lowEnd", preferredHistoryStation("current", listOf("noEnd", "lowEnd", "current"), latest, null))
        assertEquals("lowEnd", preferredHistoryStation("current", listOf("lowEnd", "noEnd", "current"), latest, null))
        assertEquals("noEnd", preferredHistoryStation("current", listOf("noEnd", "current"), latest, null))
    }

    @Test
    fun retainedProducerCopiesAreSeparateFromUntimedAndPendingFiles() {
        val own = file("current", "current", dated = false)
        val retained = file("current", "earlier", dated = true)
        val pending = file("current", "current", dated = false, indexed = false).copy(name = "station=current/unsynced/boot=one/pending.parquet")
        val counts = countHistoryFiles(listOf(own, retained, pending))
        assertEquals(2, counts.total)
        assertEquals(1, counts.otherStation)
        assertEquals(1, counts.untimed)
        assertEquals(1, counts.pending)
        assertEquals(0, counts.failed)
    }

    @Test
    fun latestDayFollowsThePhoneCalendarForAnEarlierStation() {
        val zone = TimeZone.of("Africa/Cairo")
        // 2026-09-29T13:00Z is the afternoon of 29 September in Cairo, so the day ends at local midnight of the 30th.
        val earlier = file("earlier", "earlier", dated = true, lastUtcNs = 1_790_686_800L * 1_000_000_000L)
        assertEquals(startOfDay(LocalDate(2026, 9, 30), zone), latestHistoryDayEnd(listOf(earlier), zone))
    }

    @Test
    fun archiveStationsListTheMostRecentLast() {
        val old = file("old", "old", dated = true, lastUtcNs = 1_000)
        val recent = file("recent", "recent", dated = true, lastUtcNs = 2_000)
        val untimed = file("untimed", "untimed", dated = false)
        assertEquals(listOf("untimed", "old", "recent"), archiveStations(listOf(recent, untimed, old)))
    }

    @Test
    fun aStationWithNoTimedReadingRanksBelowEvenTheLowestRealEnd() {
        // A sentinel low end would tie with a real one and fall back to the name order.
        val lowest = file("a", "a", dated = true, lastUtcNs = Long.MIN_VALUE)
        val untimed = file("b", "b", dated = false)
        assertEquals(listOf("b", "a"), archiveStations(listOf(lowest, untimed)))
    }

    @Test
    fun simpleOffersCorrectedPm25OnlyAndDetailedOffersCorrectedThenRaw() {
        val both = listOf(Metrics.PM25_CORRECTED, Metrics.PM25, Metrics.AMBIENT_TEMP)
        assertEquals(listOf(Metrics.PM25_CORRECTED, Metrics.AMBIENT_TEMP), pickerMetrics(both, DetailLevel.SIMPLE))
        assertEquals(both, pickerMetrics(both, DetailLevel.DETAILED))
        // No humidity anywhere, so Simple keeps raw PM2.5, labelled raw.
        val rawOnly = listOf(Metrics.PM25, Metrics.AMBIENT_TEMP)
        assertEquals(rawOnly, pickerMetrics(rawOnly, DetailLevel.SIMPLE))
    }

    @Test
    fun simpleOffersOnlyTheProfilePrimaryMetrics() {
        val measured = listOf(Metrics.PM25_CORRECTED, Metrics.PM25, Metrics.PM10, Metrics.PARTICLES_GT03, Metrics.BOARD_TEMP, Metrics.PARTICLES_GT25)
        assertTrue(Metrics.PARTICLES_GT03.primary.not() && Metrics.PARTICLES_GT25.primary.not() && Metrics.PM10.primary)
        assertEquals(listOf(Metrics.PM25_CORRECTED, Metrics.PM10, Metrics.BOARD_TEMP), pickerMetrics(measured, DetailLevel.SIMPLE))
        assertEquals(measured, pickerMetrics(measured, DetailLevel.DETAILED))
        assertEquals(measured, pickerMetrics(measured, DetailLevel.DEVELOPER))
        // A board whose profile names no primary column still offers its metrics in Simple.
        val noPrimary = listOf(Metrics.PARTICLES_GT03, Metrics.PARTICLES_GT25)
        assertEquals(noPrimary, pickerMetrics(noPrimary, DetailLevel.SIMPLE))
    }
}
