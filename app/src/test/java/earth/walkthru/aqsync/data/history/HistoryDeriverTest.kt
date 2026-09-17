package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.data.local.database.ArchivedFile
import earth.walkthru.aqsync.data.local.database.ArchivedFileDao
import earth.walkthru.aqsync.data.local.database.DerivedDay
import earth.walkthru.aqsync.data.local.database.DerivedDayDao
import earth.walkthru.aqsync.data.local.database.MetricBucket
import earth.walkthru.aqsync.data.local.database.MetricBucketDao
import earth.walkthru.aqsync.data.sync.FileArchive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The deriver over in-memory DAOs and a temp archive: staleness by source signature, tiers and daily files together. */
class HistoryDeriverTest {

    private fun bytes(path: String) = checkNotNull(javaClass.classLoader?.getResourceAsStream("parquet/$path")).readBytes()
    private val station = "53315f5f-cb85-4d8d-b623-d56266084189"

    @Test
    fun `deriver rebuilds a day only when its source set changes`() = runBlocking {
        val tmp = File.createTempFile("aq-derive", "").apply { delete(); mkdirs(); deleteOnExit() }
        val archive = FileArchive(tmp)
        val files = InMemoryFiles()
        val buckets = InMemoryBuckets()
        val days = InMemoryDays()
        val indexer = HistoryIndexer(archive, files)
        val store = DerivedStore(tmp)
        val deriver = HistoryDeriver(archive, files, buckets, days, store, indexer)

        fun put(name: String, resource: String, crc: Long) {
            archive.fileFor(station, name).apply { parentFile!!.mkdirs(); writeBytes(bytes(resource)) }
            files.rows += indexer.indexed(ArchivedFile(station = station, name = name, bytes = 1, crc32 = crc, sha256 = "s", syncedAtEpochMs = 1, sourceDevice = "fake"), archive.fileFor(station, name))
        }
        put("station=$station/year=2026/month=09/day=17/data_0930_a_2-106-2.parquet", "device/v3-board-2rg-105rows.parquet", 1)
        put("station=$station/unsynced/boot=x/data_unsynced_x_0-89-0.parquet", "device/v2-none-90rows-unsynced.parquet", 2)

        val first = deriver.deriveStale()
        assertEquals(1, first.daysChecked)
        assertEquals(1, first.daysRebuilt)
        assertEquals(105L, first.rowsAggregated)
        assertEquals(4, buckets.rows.map { it.resolution to it.startEpochS }.distinct().size) // 2 × 15 min + 1 h + 1 day, one row per metric each
        assertTrue(buckets.rows.any { it.metricId == "pm25" } && buckets.rows.any { it.metricId == "board_c" } && buckets.rows.none { it.metricId == "rh" })
        assertEquals(1, days.rows.size)
        // The derived daily file exists, is read back by our own reader, and carries the manifest index column.
        val dayFile = store.dayFile(station, 20713)
        assertTrue(dayFile.isFile)
        assertEquals("station/day=2026-09-17.parquet".substringAfter('/'), dayFile.name)
        val daily = earth.walkthru.aqsync.data.history.parquet.ParquetFile.open(dayFile)
        assertEquals(105L, daily.numRows)
        assertEquals(1, daily.rowGroupCount) // 09:42–09:59Z is one UTC hour
        assertEquals(78, daily.leaves.size) // 77 + source_file_index
        assertTrue(daily.leaf("event_time_utc_ns").isTimestampNanosUtc)
        assertEquals("telemetry-derived-day", daily.metadata.keyValueMetadata["purpose"])
        assertTrue(daily.metadata.keyValueMetadata["complete"] in setOf("true", "false"))
        assertTrue(store.manifestFile(station, 20713).readText().contains("\"rows_used\": 105"))
        assertEquals("day=2026-09-17.parquet", days.rows.single().dailyFile?.substringAfter('/'))
        // For tools/validate_parquet_samples.py (PyArrow + DuckDB): a real derived day built from the board's file.
        File("build/parquet-samples").apply { mkdirs() }.let { dir ->
            dayFile.copyTo(File(dir, "derived-day.parquet"), overwrite = true)
            store.manifestFile(station, 20713).copyTo(File(dir, "derived-day.manifest.json"), overwrite = true)
        }

        val second = deriver.deriveStale()
        assertEquals(0, second.daysRebuilt)

        // A second file for the same day (here: the same bytes under another name and CRC) changes the signature.
        put("station=$station/year=2026/month=09/day=17/data_1000_a_107-211-3.parquet", "device/v3-board-2rg-105rows.parquet", 3)
        val third = deriver.deriveStale()
        assertEquals(1, third.daysRebuilt)
        assertEquals(210L, third.rowsAggregated) // rows read
        assertEquals(105, buckets.rows.first { it.resolution == 86_400L && it.metricId == "pm25" }.rows) // rows counted: duplicates (same boot + sequence) skipped, like the daily file
        assertEquals(2, days.rows.single().sourceFiles)
        // Same rows under two names: the daily file de-duplicates on (boot, sequence) — 105 rows, 2 sources in the manifest.
        assertEquals(105L, earth.walkthru.aqsync.data.history.parquet.ParquetFile.open(store.dayFile(station, 20713)).numRows)
        assertTrue(store.manifestFile(station, 20713).readText().contains("\"rows_used\": 0"))

        // A second real v6 file (the full 10:00–10:29Z window, 180 rows) joins the day: 285 rows, two UTC hours → two row groups.
        put("station=$station/year=2026/month=09/day=17/data_1000_495ccef9f3eb8c5917070c921c9baec8_107-286-3.parquet", "device/v3-board-2rg-180rows.parquet", 4)
        val fifth = deriver.deriveStale()
        assertEquals(1, fifth.daysRebuilt)
        val twoWindows = earth.walkthru.aqsync.data.history.parquet.ParquetFile.open(store.dayFile(station, 20713))
        assertEquals(285L, twoWindows.numRows)
        assertEquals(2, twoWindows.rowGroupCount)
        assertEquals(listOf(105L, 180L), twoWindows.metadata.rowGroups.map { it.numRows })
        val seq = twoWindows.readColumn("sequence") as earth.walkthru.aqsync.data.history.parquet.ColumnValues.Int64
        for (i in 1 until seq.size) assertEquals("contiguous across the two device files", seq.values[i - 1] + 1, seq.values[i])
        assertEquals(285, buckets.rows.first { it.resolution == 86_400L && it.metricId == "pm25" }.rows)
        File("build/parquet-samples").apply { mkdirs() }.let { dir ->
            store.dayFile(station, 20713).copyTo(File(dir, "derived-day-two-windows.parquet"), overwrite = true)
            store.manifestFile(station, 20713).copyTo(File(dir, "derived-day-two-windows.manifest.json"), overwrite = true)
        }

        // Removing every dated file removes the day's tiers.
        files.rows.removeAll { it.name.contains("day=17") }
        val fourth = deriver.deriveStale()
        assertEquals(1, fourth.daysRebuilt)
        assertTrue(buckets.rows.isEmpty())
        assertTrue(days.rows.isEmpty())
        assertFalse(store.dayFile(station, 20713).exists())
    }

    private class InMemoryFiles : ArchivedFileDao {
        val rows = mutableListOf<ArchivedFile>()
        override fun observeAll(): Flow<List<ArchivedFile>> = flowOf(rows.toList())
        override fun observeForStation(station: String): Flow<List<ArchivedFile>> = flowOf(rows)
        override suspend fun forStation(station: String) = rows.filter { it.station == station }
        override suspend fun get(station: String, name: String) = rows.firstOrNull { it.station == station && it.name == name }
        override fun observeCount(): Flow<Int> = flowOf(rows.size)
        override fun observeTotalBytes(): Flow<Long> = flowOf(rows.sumOf { it.bytes })
        override suspend fun upsert(file: ArchivedFile) {
            rows.removeAll { it.station == file.station && it.name == file.name }
            rows += file
        }
        override suspend fun delete(station: String, name: String) {
            rows.removeAll { it.station == station && it.name == name }
        }
        override suspend fun unindexed(limit: Int) = rows.filter { it.indexedAtEpochMs == null || it.indexError != null }.take(limit)
        override fun observeUnindexedCount(): Flow<Int> = flowOf(0)
        override suspend fun withUtcBetween(station: String, fromNs: Long, toNs: Long) =
            rows.filter { it.station == station && it.indexError == null && (it.utcRows ?: 0) > 0 && (it.purpose == null || it.purpose == "telemetry") && (it.lastUtcNs ?: Long.MIN_VALUE) >= fromNs && (it.firstUtcNs ?: Long.MAX_VALUE) < toNs }
                .sortedBy { it.firstUtcNs }
        override suspend fun indexedForStation(station: String) = rows.filter { it.station == station && it.indexed }
        override suspend fun stations() = rows.map { it.station }.distinct()
    }

    private class InMemoryBuckets : MetricBucketDao {
        val rows = mutableListOf<MetricBucket>()
        override fun observe(station: String, metricId: String, resolution: Long, fromS: Long, toS: Long): Flow<List<MetricBucket>> = flowOf(rows)
        override fun observeFirstDay(station: String): Flow<Long?> = flowOf(null)
        override fun observeLastDay(station: String): Flow<Long?> = flowOf(null)
        override fun observeDayCount(station: String): Flow<Int> = flowOf(0)
        override fun observeAllDays(metricId: String): Flow<List<MetricBucket>> = flowOf(rows.filter { it.resolution == 86_400L && it.metricId == metricId })
        override suspend fun deleteBetween(station: String, fromS: Long, toS: Long) {
            rows.removeAll { it.station == station && it.startEpochS >= fromS && it.startEpochS < toS }
        }
        override suspend fun insertAll(rows: List<MetricBucket>) {
            this.rows += rows
        }
    }

    private class InMemoryDays : DerivedDayDao {
        val rows = mutableListOf<DerivedDay>()
        override suspend fun forStation(station: String) = rows.filter { it.station == station }
        override suspend fun get(station: String, epochDay: Long) = rows.firstOrNull { it.station == station && it.epochDay == epochDay }
        override suspend fun upsert(day: DerivedDay) {
            rows.removeAll { it.station == day.station && it.epochDay == day.epochDay }
            rows += day
        }
        override suspend fun delete(station: String, epochDay: Long) {
            rows.removeAll { it.station == station && it.epochDay == epochDay }
        }
        override fun observeCount(): Flow<Int> = flowOf(rows.size)
        override suspend fun stations() = rows.map { it.station }.distinct()
    }
}
