package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.archive.ArchiveIndex
import earth.walkthru.aqsync.archive.ArchiveKey
import earth.walkthru.aqsync.archive.ArchiveRecord
import earth.walkthru.aqsync.archive.ArchiveStore
import earth.walkthru.aqsync.archive.PartialMeta
import earth.walkthru.aqsync.archive.PartialWriter
import earth.walkthru.aqsync.data.history.parquet.ByteArrayReader
import earth.walkthru.aqsync.data.history.parquet.ByteReader
import earth.walkthru.aqsync.data.history.parquet.ColumnValues
import earth.walkthru.aqsync.data.history.parquet.MemoryRangeSource
import earth.walkthru.aqsync.data.history.parquet.ParquetFile
import earth.walkthru.aqsync.data.history.parquet.ParquetWriter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #63. The Day view reads a day's derived file when one is current and gives the same points as the
 * raw files, across midnight, in a zone whose day spans two UTC days, with a later sync of the same rows.
 */
class DerivedDayViewTest {
    private val day = 20_000L

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

    /** The 180 row board file with its UTC spread over 36 hours from 06:00 of [day], rows before [nullBefore] without UTC. */
    private fun respread(nullBefore: Int = 0): ByteArray {
        val raw = ParquetFile.wrap(fixtureBytes("parquet/device/v3-board-2rg-180rows.parquet"))
        val values = raw.leaves.map { leaf ->
            val col = raw.readColumn(leaf)
            if (leaf.name != ParquetIndexer.UTC) {
                col
            } else {
                val step = 36 * 3_600L / col.size
                ColumnValues.Int64(LongArray(col.size) { i -> (day * 86_400L + 6 * 3_600L + i * step) * 1_000_000_000L }, BooleanArray(col.size) { it >= nullBefore })
            }
        }
        val sink = MemorySink()
        val writer = ParquetWriter(sink, raw.leaves.map { ParquetWriter.Column(it.name, it.type, it.isTimestampNanosUtc) }, keyValueMetadata = raw.metadata.keyValueMetadata)
        writer.begin()
        writer.rowGroup(values)
        writer.finish()
        return sink.toByteArray()
    }

    @Test
    fun theDerivedDayGivesTheRawPointsWithoutOpeningARawFile() = runTest {
        val first = respread()
        val station = ParquetFile.wrap(first).metadata.stationId ?: "derived-station"
        val store = Store()
        val index = Index()
        for ((i, bytes) in listOf(first, respread(nullBefore = 70)).withIndex()) {
            val r = ArchiveRecord(station, "station=$station/day/part-$i.parquet", bytes.size.toLong(), i.toLong(), "s$i", 1, "fake")
            store.files[r.key] = bytes
            index.put(r)
        }
        ArchiveIndexRepair(store, index, nowEpochMs = { 42L }).backfillAll()
        val tiers = ArchiveHistoryTiers(store, index)
        var opened = 0
        val derived = DerivedDayFiles { s, epochDay, sources, anchors ->
            val sink = MemorySink()
            val pass = DayPass.run(s, epochDay, sources.map { r -> DailyParquetSource(r) { ParquetFile.wrap(store.files.getValue(r.key), r.name) } }, sink, anchors, (epochDay + 2) * 86_400, tiers = false)
            pass.daily?.let {
                opened++
                MemoryRangeSource(sink.toByteArray(), "derived/$epochDay")
            }
        }
        val raw = HistorySeriesReader(store, index, tiers)
        val fromDerived = HistorySeriesReader(store, index, tiers, derived = derived)
        // Ends inside the second UTC day, so the window takes rows from both days and both sides of midnight.
        val ends = listOf((day + 1) * 86_400 + 9 * 3_600, (day + 1) * 86_400 + 18 * 3_600)
        for (zone in listOf(TimeZone.UTC, TimeZone.of("Asia/Kolkata"))) {
            for (end in ends) {
                val window = HistoryWindow.endingAt(end, HistoryRange.DAY, zone)
                for (metric in listOf(Metrics.PM25, Metrics.BOARD_TEMP)) {
                    val expected = raw.observe(station, metric, HistoryRange.DAY, window).first()
                    store.reads = 0
                    val actual = fromDerived.observe(station, metric, HistoryRange.DAY, window).first()
                    assertEquals(0, store.reads, "no raw file opened for $zone $end")
                    assertTrue(expected.points.isNotEmpty(), "the window holds rows for $zone $end")
                    assertEquals(expected.points, actual.points, "$metric $zone $end")
                    assertEquals(expected.validRows, actual.validRows)
                    assertEquals(expected.mean, actual.mean)
                    assertEquals(expected.max, actual.max)
                    assertEquals(expected.estimatedRows, actual.estimatedRows)
                }
            }
        }
        assertTrue(opened > 0)
    }

    @Test
    fun aDayWithoutADerivedFileReadsTheRawFiles() = runTest {
        val bytes = respread()
        val station = ParquetFile.wrap(bytes).metadata.stationId ?: "derived-station"
        val store = Store()
        val index = Index()
        val r = ArchiveRecord(station, "station=$station/day/part.parquet", bytes.size.toLong(), 1, "s", 1, "fake")
        store.files[r.key] = bytes
        index.put(r)
        ArchiveIndexRepair(store, index, nowEpochMs = { 42L }).backfillAll()
        val tiers = ArchiveHistoryTiers(store, index)
        val window = HistoryWindow.endingAt((day + 1) * 86_400 + 9 * 3_600, HistoryRange.DAY, TimeZone.UTC)
        val expected = HistorySeriesReader(store, index, tiers).observe(station, Metrics.PM25, HistoryRange.DAY, window).first()
        val broken = DerivedDayFiles { _, _, _, _ -> MemoryRangeSource(ByteArray(64), "broken") }
        val actual = HistorySeriesReader(store, index, tiers, derived = broken).observe(station, Metrics.PM25, HistoryRange.DAY, window).first()
        assertEquals(expected.points, actual.points)
        assertTrue(actual.points.isNotEmpty())
    }
}
