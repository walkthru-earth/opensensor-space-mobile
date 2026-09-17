package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.data.history.parquet.ParquetFile
import earth.walkthru.aqsync.data.local.database.ArchivedFile
import earth.walkthru.aqsync.data.local.database.ArchivedFileDao
import earth.walkthru.aqsync.data.sync.FileArchive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ParquetIndexerTest {

    private fun bytes(path: String) = checkNotNull(javaClass.classLoader?.getResourceAsStream("parquet/$path")).readBytes()

    private fun expected(label: String) = Json.parseToJsonElement(
        checkNotNull(javaClass.classLoader?.getResourceAsStream("parquet/expected.json")).bufferedReader().use { it.readText() }
    ).jsonObject["files"]!!.jsonArray.map { it.jsonObject }.first { it["label"]!!.jsonPrimitive.content == label }

    private fun column(label: String, name: String) =
        expected(label)["column_details"]!!.jsonArray.map { it.jsonObject }.first { it["name"]!!.jsonPrimitive.content == name }

    @Test
    fun `real v2 file - index comes from decoding and matches PyArrow`() {
        val index = ParquetIndexer.index(ParquetFile.wrap(bytes("device/v2-lz4-4rows-dated.parquet")))
        assertFalse(index.fromStatistics)
        assertEquals(4L, index.rows)
        assertEquals(1, index.rowGroups)
        assertEquals(77, index.columns)
        assertEquals("cores3-telemetry-v2", index.schemaVersion)
        assertEquals("telemetry", index.purpose)
        assertTrue(index.isTelemetry)
        val utc = column("v2-lz4-4rows-dated", "event_time_utc_ns")
        assertEquals(utc["valid"]!!.jsonPrimitive.long, index.utcRows)
        assertEquals(utc["min"]!!.jsonPrimitive.long, index.firstUtcNs)
        assertEquals(utc["max"]!!.jsonPrimitive.long, index.lastUtcNs)
        val mono = column("v2-lz4-4rows-dated", "monotonic_us")
        assertEquals(mono["min"]!!.jsonPrimitive.long, index.firstMonoUs)
        assertEquals(mono["max"]!!.jsonPrimitive.long, index.lastMonoUs)
        val seq = column("v2-lz4-4rows-dated", "sequence")
        assertEquals(seq["min"]!!.jsonPrimitive.long, index.firstSeq)
        assertEquals(seq["max"]!!.jsonPrimitive.long, index.lastSeq)
    }

    @Test
    fun `unsynced file has no UTC span but a monotonic one`() {
        val index = ParquetIndexer.index(ParquetFile.wrap(bytes("device/v1-none-64rows-unsynced.parquet")))
        assertEquals(0L, index.utcRows)
        assertNull(index.firstUtcNs)
        assertFalse(index.hasUtc)
        assertNotNull(index.firstMonoUs)
        assertTrue(index.lastMonoUs!! > index.firstMonoUs!!)
        assertEquals(64L, index.rows)
    }

    @Test
    fun `v3 file - statistics fast path agrees with decoding`() {
        val file = ParquetFile.wrap(bytes("v3/v3-contract-lz4-anchored.parquet"))
        val index = ParquetIndexer.index(file)
        assertTrue("v6 footer statistics used", index.fromStatistics)
        assertEquals("cores3-telemetry-v3", index.schemaVersion)
        for (name in listOf(ParquetIndexer.UTC, ParquetIndexer.MONO, ParquetIndexer.SEQ)) {
            val leaf = file.leaf(name)
            val stats = ParquetIndexer.rangeFromStatistics(file, leaf)
            val decoded = ParquetIndexer.rangeByDecoding(file.readColumn(leaf))
            assertEquals(name, decoded, stats)
        }
        // 89 of 90 rows have pms_status 4 in the contract fixture (row 0 has no PMS frame yet).
        assertEquals(89L, index.pmValidRows)
    }

    @Test
    fun `v3 multi-row-group - statistics span all groups`() {
        val file = ParquetFile.wrap(bytes("v3/v3-hourly-lz4.parquet"))
        val leaf = file.leaf("time_ms")
        val stats = ParquetIndexer.rangeFromStatistics(file, leaf)!!
        val decoded = ParquetIndexer.rangeByDecoding(file.readColumn(leaf))
        assertEquals(decoded, stats)
        assertEquals(360L, stats.count)
    }

    @Test
    fun `history indexer fills the Room row and records a reader refusal instead of throwing`() = runBlocking {
        val tmp = File.createTempFile("aq-archive", "").apply { delete(); mkdirs(); deleteOnExit() }
        val archive = FileArchive(tmp)
        val dao = InMemoryDao()
        val indexer = HistoryIndexer(archive, dao)
        val station = "53315f5f-cb85-4d8d-b623-d56266084189"
        val good = "station=$station/year=2026/month=09/day=08/data_2015_763038f2396a5d68927ac324c4a744a2_3-6-1.parquet"
        val bogus = "station=$station/year=2026/month=09/day=08/data_bogus.parquet"
        archive.fileFor(station, good).apply { parentFile!!.mkdirs(); writeBytes(bytes("device/v2-lz4-4rows-dated.parquet")) }
        archive.fileFor(station, bogus).apply { parentFile!!.mkdirs(); writeBytes("PAR1 this is not parquet PAR1".toByteArray()) }
        dao.rows += ArchivedFile(station = station, name = good, bytes = 9821, crc32 = 1, sha256 = "x", syncedAtEpochMs = 1, sourceDevice = "fake")
        dao.rows += ArchivedFile(station = station, name = bogus, bytes = 27, crc32 = 2, sha256 = "y", syncedAtEpochMs = 1, sourceDevice = "fake")
        dao.rows += ArchivedFile(station = station, name = "station=$station/unsynced/boot=0/missing.parquet", bytes = 0, crc32 = 3, sha256 = "z", syncedAtEpochMs = 1, sourceDevice = "fake")

        assertEquals(3, indexer.backfill())
        val g = dao.rows.first { it.name == good }
        assertTrue(g.indexed)
        assertEquals(4L, g.rows)
        assertEquals("cores3-telemetry-v2", g.schemaVersion)
        assertNotNull(g.firstUtcNs)
        val b = dao.rows.first { it.name == bogus }
        assertFalse(b.indexed)
        assertTrue(b.indexError!!.contains("footer") || b.indexError!!.contains("PAR1") || b.indexError!!.contains("small"))
        assertEquals("file missing from archive", dao.rows.first { it.name.endsWith("missing.parquet") }.indexError)
        // A second pass retries the failures, finds nothing new and stops the loop.
        assertEquals(0, indexer.backfill())
        // The time-range query sees only the indexed telemetry file.
        val hit = dao.withUtcBetween(station, g.firstUtcNs!!, g.lastUtcNs!! + 1)
        assertEquals(listOf(good), hit.map { it.name })
    }

    private class InMemoryDao : ArchivedFileDao {
        val rows = mutableListOf<ArchivedFile>()
        override fun observeAll(): Flow<List<ArchivedFile>> = flowOf(rows)
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
        override suspend fun unindexed(limit: Int) = rows.filter { it.indexedAtEpochMs == null || it.indexError != null }.sortedBy { it.indexedAtEpochMs != null }.take(limit)
        override fun observeUnindexedCount(): Flow<Int> = flowOf(rows.count { it.indexedAtEpochMs == null || it.indexError != null })
        override suspend fun withUtcBetween(station: String, fromNs: Long, toNs: Long) =
            rows.filter { it.station == station && it.indexError == null && (it.utcRows ?: 0) > 0 && (it.lastUtcNs ?: Long.MIN_VALUE) >= fromNs && (it.firstUtcNs ?: Long.MAX_VALUE) < toNs }
    }
}
