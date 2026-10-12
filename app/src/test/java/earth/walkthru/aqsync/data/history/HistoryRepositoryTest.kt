package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.data.local.database.ArchivedFile
import earth.walkthru.aqsync.data.local.database.DerivedArchivedFileQueries
import earth.walkthru.aqsync.data.local.database.MetricBucket
import earth.walkthru.aqsync.data.local.database.MetricBucketDao
import earth.walkthru.aqsync.data.sync.FileArchive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.toKotlinLocalDate
import kotlinx.datetime.toKotlinTimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class HistoryRepositoryTest {

    private fun bytes(path: String) = checkNotNull(javaClass.classLoader?.getResourceAsStream("parquet/$path")).readBytes()
    private val station = "53315f5f-cb85-4d8d-b623-d56266084189"
    private val dayStart = 1_789_603_200L // 2026-09-17T00:00Z

    // Every fixture window is complete, so coverage is not cut at the real clock.
    private val afterFixtures: () -> Long = { 4_102_444_800L } // 2100-01-01T00:00Z
    private fun window(range: HistoryRange, toS: Long) = HistoryWindow.endingAt(toS, range, ZoneId.of("UTC").toKotlinTimeZone())

    @Test
    fun `hourly local day query includes both fall hours and uses elapsed coverage`() = runBlocking {
        val zone = ZoneId.of("America/New_York")
        val fall = HistoryWindow.endingOn(LocalDate.of(2026, 11, 2).toKotlinLocalDate(), HistoryRange.DAY, zone.toKotlinTimeZone())
        val firstOne = LocalDateTime.of(2026, 11, 1, 1, 0).atOffset(ZoneOffset.ofHours(-4)).toEpochSecond()
        val secondOne = LocalDateTime.of(2026, 11, 1, 1, 0).atOffset(ZoneOffset.ofHours(-5)).toEpochSecond()
        val buckets = Buckets().apply {
            rows += MetricBucket(station = station, metricId = Metrics.PM25.id, resolution = Resolution.HOUR.seconds, startEpochS = firstOne, rows = 360, validRows = 360, estimatedRows = 0, mean = 10f, min = 10f, max = 10f)
            rows += MetricBucket(station = station, metricId = Metrics.PM25.id, resolution = Resolution.HOUR.seconds, startEpochS = secondOne, rows = 360, validRows = 360, estimatedRows = 0, mean = 20f, min = 20f, max = 20f)
        }
        val tmp = File.createTempFile("aq-hist-dst", "").apply { delete(); mkdirs(); deleteOnExit() }
        val repo = HistoryRepository(FileArchive(tmp), Files(), buckets, DerivedDayFiles.NONE, afterFixtures)
        val result = repo.observeHourlyDay(station, Metrics.PM25, fall).first()
        assertEquals(25 * 360L, result.expectedRows)
        assertEquals(720L, result.validRows)
        assertEquals(15f, result.mean)
        assertEquals(2f / 25f, result.coverage, 1e-6f)
        assertEquals(setOf(firstOne, secondOne), result.points.map { it.startS }.toSet())

        val spring = HistoryWindow.endingOn(LocalDate.of(2026, 3, 9).toKotlinLocalDate(), HistoryRange.DAY, zone.toKotlinTimeZone())
        val springResult = repo.observeHourlyDay(station, Metrics.PM25, spring).first()
        assertEquals(23 * 360L, springResult.expectedRows)
    }

    @Test
    fun `half-hour local midnight reads raw edge instead of including the preceding UTC bucket`() = runBlocking {
        val zone = ZoneId.of("Asia/Kolkata")
        val day = HistoryWindow.endingOn(LocalDate.of(2026, 9, 10).toKotlinLocalDate(), HistoryRange.DAY, zone.toKotlinTimeZone())
        val tmp = File.createTempFile("aq-hist-half-hour", "").apply { delete(); mkdirs(); deleteOnExit() }
        val archive = FileArchive(tmp)
        val files = Files()
        val name = "station=$station/year=2026/month=09/day=08/data_1844.parquet"
        val file = archive.fileFor(station, name).apply { parentFile!!.mkdirs(); writeBytes(bytes("device/v1-none-1row-dated.parquet")) }
        files.rows += HistoryIndexer(archive, files).indexed(
            ArchivedFile(station = station, name = name, bytes = file.length(), crc32 = 1, sha256 = "s", syncedAtEpochMs = 1, sourceDevice = "fake"),
            file,
        )
        val utcBucketStart = day.fromS - 1_800
        val buckets = Buckets().apply {
            rows += MetricBucket(station = station, metricId = Metrics.PM25.id, resolution = Resolution.HOUR.seconds, startEpochS = utcBucketStart, rows = 360, validRows = 360, estimatedRows = 0, mean = 999f, min = 999f, max = 999f)
        }
        val result = HistoryRepository(archive, files, buckets, DerivedDayFiles.NONE, afterFixtures).observeHourlyDay(station, Metrics.PM25, day).first()
        assertEquals(1L, result.validRows)
        assertEquals(31f, result.mean!!, 1e-6f)
        assertEquals(1, result.points.size)
        assertTrue(result.points.single().startS in day.fromS until day.toS)
    }

    @Test
    fun `day view reads the rows, week view reads the tier`() = runBlocking {
        val tmp = File.createTempFile("aq-hist", "").apply { delete(); mkdirs(); deleteOnExit() }
        val archive = FileArchive(tmp)
        val files = Files()
        val buckets = Buckets()
        val name = "station=$station/year=2026/month=09/day=17/data_0930_a_2-106-2.parquet"
        archive.fileFor(station, name).apply { parentFile!!.mkdirs(); writeBytes(bytes("device/v3-board-2rg-105rows.parquet")) }
        files.rows += HistoryIndexer(archive, files).indexed(ArchivedFile(station = station, name = name, bytes = 1, crc32 = 1, sha256 = "s", syncedAtEpochMs = 1, sourceDevice = "fake"), archive.fileFor(station, name))
        val agg = Aggregator(station).also { it.add(earth.walkthru.aqsync.data.history.parquet.ParquetFile.wrap(bytes("device/v3-board-2rg-105rows.parquet"))) }
        buckets.rows += agg.buckets().flatMap { b -> b.metrics.map { (id, a) -> MetricBucket(station = station, metricId = id, resolution = b.resolution.seconds, startEpochS = b.startEpochS, rows = b.rows, validRows = a.n, estimatedRows = b.estimatedRows, mean = a.mean, min = a.min, max = a.max) } }

        val repo = HistoryRepository(archive, files, buckets, DerivedDayFiles.NONE, afterFixtures)

        // Day: 24 h ending at midnight after the data → 105 raw points (below the binning cap).
        val day = repo.observe(station, Metrics.PM25, HistoryRange.DAY, window(HistoryRange.DAY, dayStart + 86_400)).first()
        assertFalse(day.binned)
        assertEquals(105, day.points.size)
        assertEquals(105L, day.validRows)
        assertEquals(27.93f, day.mean!!, 0.01f)
        assertEquals(105f / 8640f, day.coverage, 1e-6f)
        assertTrue(day.points.all { it.endS - it.startS == 10L })
        // Board temperature is not gated by pms_status and has no envelope.
        val temp = repo.observe(station, Metrics.BOARD_TEMP, HistoryRange.DAY, window(HistoryRange.DAY, dayStart + 86_400)).first()
        assertEquals(41.40f, temp.mean!!, 0.01f)
        assertNull(temp.points.first().min)

        // A day with no data is empty, not an error.
        val empty = repo.observe(station, Metrics.PM25, HistoryRange.DAY, window(HistoryRange.DAY, dayStart - 86_400)).first()
        assertTrue(empty.isEmpty)
        assertEquals(0, empty.points.size)

        // Week: 15-minute buckets, weighted mean equals the row mean, envelope present.
        val week = repo.observe(station, Metrics.PM25, HistoryRange.WEEK, window(HistoryRange.WEEK, dayStart + 86_400)).first()
        assertEquals(2, week.points.size)
        assertEquals(27.93f, week.mean!!, 0.01f)
        assertTrue(week.points.all { it.min != null && it.max != null })
        assertEquals(1f, week.points[1].coverage, 1e-6f)
        assertEquals(15f / 90f, week.points[0].coverage, 1e-6f)
    }

    @Test
    fun `day view counts duplicate boot sequences once across files`() = runBlocking {
        val tmp = File.createTempFile("aq-hist-duplicates", "").apply { delete(); mkdirs(); deleteOnExit() }
        val archive = FileArchive(tmp)
        val files = Files()
        val fixture = bytes("device/v3-board-2rg-105rows.parquet")
        for ((index, name) in listOf(
            "station=$station/year=2026/month=09/day=17/data_0930_a_2-106-2.parquet",
            "station=$station/year=2026/month=09/day=17/data_1000_a_107-211-3.parquet",
        ).withIndex()) {
            val file = archive.fileFor(station, name).apply { parentFile!!.mkdirs(); writeBytes(fixture) }
            files.rows += HistoryIndexer(archive, files).indexed(
                ArchivedFile(station = station, name = name, bytes = fixture.size.toLong(), crc32 = index.toLong(), sha256 = "s", syncedAtEpochMs = 1, sourceDevice = "fake"),
                file,
            )
        }

        val day = HistoryRepository(archive, files, Buckets(), DerivedDayFiles.NONE, afterFixtures)
            .observe(station, Metrics.PM25, HistoryRange.DAY, window(HistoryRange.DAY, dayStart + 86_400)).first()
        assertEquals(105, day.points.size)
        assertEquals(105L, day.validRows)
        assertEquals(27.93f, day.mean!!, 0.01f)
        assertEquals(105f / 8640f, day.coverage, 1e-6f)
    }

    private class Files : DerivedArchivedFileQueries {
        val rows = mutableListOf<ArchivedFile>()
        override fun observeAll(): Flow<List<ArchivedFile>> = flowOf(rows.toList())
        override fun observeForStation(station: String): Flow<List<ArchivedFile>> = flowOf(rows.toList())
        override suspend fun forStation(station: String) = rows.filter { it.station == station }
        override suspend fun get(station: String, name: String) = rows.firstOrNull { it.station == station && it.name == name }
        override fun observeCount(): Flow<Int> = flowOf(rows.size)
        override fun observeTotalBytes(): Flow<Long> = flowOf(rows.sumOf { it.bytes })
        override suspend fun upsert(file: ArchivedFile) { rows.removeAll { it.station == file.station && it.name == file.name }; rows += file }
        override suspend fun insertIfAbsent(file: ArchivedFile): Long = if (rows.any { it.station == file.station && it.name == file.name }) -1L else { rows += file; rows.size.toLong() }
        override suspend fun delete(station: String, name: String) { rows.removeAll { it.station == station && it.name == name } }
        override suspend fun unindexed(limit: Int) = emptyList<ArchivedFile>()
        override fun observeUnindexedCount(): Flow<Int> = flowOf(0)
        override suspend fun withUtcBetween(station: String, fromNs: Long, toNs: Long) =
            rows.filter { it.station == station && it.indexError == null && (it.utcRows ?: 0) > 0 && (it.lastUtcNs ?: Long.MIN_VALUE) >= fromNs && (it.firstUtcNs ?: Long.MAX_VALUE) < toNs }
        override suspend fun indexedForStation(station: String) = rows.filter { it.station == station && it.indexed }
        override suspend fun stations() = rows.map { it.station }.distinct()
    }

    private class Buckets : MetricBucketDao {
        val rows = mutableListOf<MetricBucket>()
        override fun observe(station: String, metricId: String, resolution: Long, fromS: Long, toS: Long): Flow<List<MetricBucket>> =
            flowOf(rows.filter { it.station == station && it.metricId == metricId && it.resolution == resolution && it.startEpochS >= fromS && it.startEpochS < toS }.sortedBy { it.startEpochS })
        override fun observeFirstDay(station: String): Flow<Long?> = flowOf(null)
        override fun observeLastDay(station: String): Flow<Long?> = flowOf(null)
        override fun observeDayCount(station: String): Flow<Int> = flowOf(0)
        override fun observeAllDays(metricId: String): Flow<List<MetricBucket>> = flowOf(rows.filter { it.resolution == 86_400L && it.metricId == metricId })
        override suspend fun deleteBetween(station: String, fromS: Long, toS: Long) { rows.removeAll { it.startEpochS >= fromS && it.startEpochS < toS } }
        override suspend fun insertAll(rows: List<MetricBucket>) { this.rows += rows }
    }
}
