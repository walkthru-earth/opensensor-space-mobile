package space.opensensor.data.history

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.opensensor.data.local.database.ArchivedFile
import space.opensensor.data.local.database.DerivedArchivedFileQueries
import space.opensensor.data.sync.DeviceCoordinator
import space.opensensor.data.sync.FileArchive
import java.io.File

class HistoryIndexerTest {

    private fun bytes(path: String) = migrateLegacyFooter(checkNotNull(javaClass.classLoader?.getResourceAsStream("parquet/$path")).readBytes())

    @Test
    fun `history indexer fills the Room row and records a reader refusal instead of throwing`() = runBlocking {
        val tmp = File.createTempFile("aq-archive", "").apply { delete(); mkdirs(); deleteOnExit() }
        val archive = FileArchive(tmp)
        val dao = InMemoryDao()
        val indexer = HistoryIndexer(archive, dao, DeviceCoordinator())
        val thing = "53315f5f-cb85-4d8d-b623-d56266084189"
        val good = "thing=$thing/year=2026/month=09/day=08/data_2015_763038f2396a5d68927ac324c4a744a2_3-6-1.parquet"
        val bogus = "thing=$thing/year=2026/month=09/day=08/data_bogus.parquet"
        archive.fileFor(thing, good).apply { parentFile!!.mkdirs(); writeBytes(bytes("device/v2-lz4-4rows-dated.parquet")) }
        archive.fileFor(thing, bogus).apply { parentFile!!.mkdirs(); writeBytes("PAR1 this is not parquet PAR1".toByteArray()) }
        dao.rows += ArchivedFile(thing = thing, name = good, bytes = 9821, crc32 = 1, sha256 = "x", syncedAtEpochMs = 1, sourceDevice = "fake")
        dao.rows += ArchivedFile(thing = thing, name = bogus, bytes = 27, crc32 = 2, sha256 = "y", syncedAtEpochMs = 1, sourceDevice = "fake")
        dao.rows += ArchivedFile(thing = thing, name = "thing=$thing/unsynced/boot=0/missing.parquet", bytes = 0, crc32 = 3, sha256 = "z", syncedAtEpochMs = 1, sourceDevice = "fake")

        assertEquals(3, indexer.backfill())
        val g = dao.rows.first { it.name == good }
        assertTrue(g.isIndexed)
        assertEquals(4L, g.rows)
        assertEquals("telemetry", g.purpose)
        assertNotNull(g.firstUtcNs)
        val b = dao.rows.first { it.name == bogus }
        assertFalse(b.isIndexed)
        assertTrue(b.indexError!!.contains("footer") || b.indexError!!.contains("PAR1") || b.indexError!!.contains("small"))
        assertEquals("file missing from archive", dao.rows.first { it.name.endsWith("missing.parquet") }.indexError)
        // A second pass retries the failures, finds nothing new and stops the loop.
        assertEquals(0, indexer.backfill())
        // The time-range query sees only the indexed telemetry file.
        val hit = dao.withUtcBetween(thing, g.firstUtcNs!!, g.lastUtcNs!! + 1)
        assertEquals(listOf(good), hit.map { it.name })
    }

    @Test
    fun `retained earlier thing files are archived without being attributed to the reset thing`() = runBlocking {
        val tmp = java.nio.file.Files.createTempDirectory("opensensor-thing-reset").toFile()
        try {
            val archive = FileArchive(tmp)
            val name = "thing=old-thing/year=2026/month=09/day=08/retained.parquet"
            val payload = bytes("device/v2-lz4-4rows-dated.parquet")
            val file = archive.fileFor("new-thing", name).apply { parentFile!!.mkdirs(); writeBytes(payload) }
            val row = ArchivedFile(thing = "new-thing", name = name, bytes = payload.size.toLong(), crc32 = 1, sha256 = "saved", syncedAtEpochMs = 1, sourceDevice = "same-device")
            val indexed = HistoryIndexer(archive, InMemoryDao(), DeviceCoordinator()).indexed(row, file)
            assertFalse(indexed.isIndexed)
            assertTrue(indexed.indexError!!.contains("old-thing"))
            org.junit.Assert.assertArrayEquals(payload, file.readBytes())
            assertEquals("new-thing", indexed.thing)
            assertEquals(name, indexed.name)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `backfillAll estimates unsynced files even when no rows need indexing`() = runBlocking {
        val tmp = java.nio.file.Files.createTempDirectory("aq-history-estimate").toFile()
        try {
            val archive = FileArchive(tmp)
            val dao = InMemoryDao()
            val thing = "thing-1"
            val boot = "boot-1"
            dao.rows += ArchivedFile(
                thing = thing, name = "unsynced.parquet", bytes = 1, crc32 = 1, sha256 = "a", syncedAtEpochMs = 1, sourceDevice = "fake",
                indexedAtEpochMs = 1, rows = 2, utcRows = 0, bootId = boot, firstMonoUs = 1_000_000, lastMonoUs = 2_000_000,
            )
            dao.rows += ArchivedFile(
                thing = thing, name = "anchor.parquet", bytes = 1, crc32 = 2, sha256 = "b", syncedAtEpochMs = 1, sourceDevice = "fake",
                indexedAtEpochMs = 1, rows = 2, utcRows = 2, bootId = boot, firstUtcNs = 10_000_000_000, lastUtcNs = 11_000_000_000,
                firstMonoUs = 3_000_000, lastMonoUs = 4_000_000,
            )

            HistoryIndexer(archive, dao, DeviceCoordinator()).backfillAll()

            val estimated = dao.rows.first { it.name == "unsynced.parquet" }
            assertEquals(8_000_000_000L, estimated.estFirstUtcNs)
            assertEquals(9_000_000_000L, estimated.estLastUtcNs)
            assertEquals("anchor.parquet", estimated.estAnchorFile)
        } finally {
            tmp.deleteRecursively()
        }
    }

    private class InMemoryDao : DerivedArchivedFileQueries {
        val rows = mutableListOf<ArchivedFile>()
        override fun observeAll(): Flow<List<ArchivedFile>> = flowOf(rows)
        override fun observeForThing(thing: String): Flow<List<ArchivedFile>> = flowOf(rows)
        override suspend fun forThing(thing: String) = rows.filter { it.thing == thing }
        override suspend fun get(thing: String, name: String) = rows.firstOrNull { it.thing == thing && it.name == name }
        override fun observeCount(): Flow<Int> = flowOf(rows.size)
        override fun observeTotalBytes(): Flow<Long> = flowOf(rows.sumOf { it.bytes })
        override suspend fun upsert(file: ArchivedFile) {
            rows.removeAll { it.thing == file.thing && it.name == file.name }
            rows += file
        }
        override suspend fun insertIfAbsent(file: ArchivedFile): Long {
            if (rows.any { it.thing == file.thing && it.name == file.name }) return -1L
            rows += file
            return rows.size.toLong()
        }
        override suspend fun delete(thing: String, name: String) {
            rows.removeAll { it.thing == thing && it.name == name }
        }
        override suspend fun unindexed(limit: Int) = rows.filter { it.indexedAtEpochMs == null || it.indexError != null }.sortedBy { it.indexedAtEpochMs != null }.take(limit)
        override fun observeUnindexedCount(): Flow<Int> = flowOf(rows.count { it.indexedAtEpochMs == null || it.indexError != null })
        override suspend fun withUtcBetween(thing: String, fromNs: Long, toNs: Long) =
            rows.filter { it.thing == thing && it.indexError == null && (it.utcRows ?: 0) > 0 && (it.lastUtcNs ?: return@filter false) >= fromNs && (it.firstUtcNs ?: return@filter false) < toNs }
        override suspend fun indexedForThing(thing: String) = rows.filter { it.thing == thing && it.isIndexed }
        override suspend fun things() = rows.map { it.thing }.distinct()
    }
}
