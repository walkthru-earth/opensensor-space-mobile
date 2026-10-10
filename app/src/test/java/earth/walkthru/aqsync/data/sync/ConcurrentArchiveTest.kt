package earth.walkthru.aqsync.data.sync

import earth.walkthru.aqsync.data.ble.DeviceSession
import earth.walkthru.aqsync.data.fake.FakeTransport
import earth.walkthru.aqsync.data.local.database.ArchivedFile
import earth.walkthru.aqsync.data.local.database.ArchivedFileDao
import earth.walkthru.aqsync.storage.AlreadyCommitted
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32

/**
 * The shared engine over Android's adapters (`FileArchive`, Room, the History indexer). The adapter-free
 * cases live in `:core:sync` commonTest (`SyncEngineTest`).
 */
class ConcurrentArchiveTest {
    private lateinit var tmp: File
    private lateinit var archive: FileArchive
    private lateinit var engine: SyncEngine
    private val dao = InMemoryDao()
    private val transports = ArrayList<FakeTransport>()
    private val name = "legacy-parquet/retained.parquet"
    private val bytes = requireNotNull(javaClass.classLoader!!.getResourceAsStream("parquet/device/v1-none-90rows-dated.parquet")).use { it.readBytes() }

    @Before
    fun setUp() {
        tmp = Files.createTempDirectory("opensensor-concurrent-archive").toFile()
        archive = FileArchive(tmp)
        engine = androidSyncEngine(archive, dao)
    }

    @After
    fun tearDown() {
        transports.forEach { it.close() }
        tmp.deleteRecursively()
    }

    private suspend fun device(payload: ByteArray = bytes): Pair<FakeTransport, DeviceSession> {
        val fake = FakeTransport(address = "fake-${transports.size}", chunkDelayMs = 1, liveIntervalMs = 3_600_000)
        transports += fake
        fake.files.clear()
        fake.files += FakeTransport.FakeFile(name, payload)
        fake.connect()
        return fake to DeviceSession(fake)
    }

    private suspend fun download(device: Pair<FakeTransport, DeviceSession>, station: String, progress: (FileProgress) -> Unit = {}): FileOutcome =
        engine.download(device.second, station, device.first.address, name, device.first.files.single().bytes.size.toLong(), device.first.maxRead, progress)

    @Test
    fun `station identities cannot select the archive root or its parent`() {
        for (station in listOf(".", "..")) {
            try {
                archive.fileFor(station, name)
                fail("station must remain a child namespace")
            } catch (_: IllegalArgumentException) {
                assertFalse(archive.fileFor("station-a", name).exists())
            }
        }
    }

    @Test
    fun `different stations transfer the same relative filename at the same time`() = runBlocking {
        withTimeout(10_000) {
            val aBytes = stationParquetFixture(bytes, "station-a")
            val bBytes = stationParquetFixture(bytes, "station-b")
            val a = device(aBytes)
            val b = device(bBytes)
            val bothStarted = java.util.concurrent.CountDownLatch(2)
            fun started(progress: FileProgress) {
                if (progress.bytesDone == 0L) {
                    bothStarted.countDown()
                    assertTrue("distinct archive identities must open concurrently", bothStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
                }
            }
            val first = async(kotlinx.coroutines.Dispatchers.Default) { download(a, "station-a", ::started) }
            val second = async(kotlinx.coroutines.Dispatchers.Default) { download(b, "station-b", ::started) }
            val outcomes = listOf(first.await(), second.await())
            assertTrue(outcomes.all { it is FileOutcome.Synced })
            assertEquals(0, outcomes.filterIsInstance<FileOutcome.Synced>().count { it.alreadyArchived })
            assertArrayEquals(aBytes, archive.fileFor("station-a", name).readBytes())
            assertArrayEquals(bBytes, archive.fileFor("station-b", name).readBytes())
            assertEquals(2, dao.rows.size)
            assertTrue(dao.rows.values.all { it.indexed })
        }
    }

    @Test
    fun `same station and filename has one writer and the waiter rechecks verified bytes`() = runBlocking {
        withTimeout(10_000) {
            val a = device()
            val b = device()
            val first = async { download(a, "station-a") }
            val second = async { download(b, "station-a") }
            val outcomes = listOf(first.await(), second.await())
            assertTrue(outcomes.all { it is FileOutcome.Synced })
            assertEquals(1, outcomes.filterIsInstance<FileOutcome.Synced>().count { it.alreadyArchived })
            val windows = (bytes.size + a.first.maxRead - 1) / a.first.maxRead
            assertEquals(windows, a.first.readCount + b.first.readCount)
            assertEquals(1, dao.rows.size)
            assertEquals(1, dao.upserts)
            assertArrayEquals(bytes, archive.fileFor("station-a", name).readBytes())
            assertFalse(archive.partialRoot.walkTopDown().any { it.isFile })
        }
    }

    @Test
    fun `changed producer bytes never replace an existing verified archive`() = runBlocking {
        val a = device()
        assertTrue(download(a, "station-a") is FileOutcome.Synced)
        val changed = bytes.copyOf().also { it[100] = (it[100].toInt() xor 1).toByte() }
        val b = device(changed)
        assertTrue(download(b, "station-a") is FileOutcome.Mismatch)
        assertEquals(0, b.first.readCount)
        assertArrayEquals(bytes, archive.fileFor("station-a", name).readBytes())
        assertEquals(1, dao.upserts)
    }

    @Test
    fun `corrupted local archive is preserved and surfaced instead of silently overwritten`() = runBlocking {
        val a = device()
        assertTrue(download(a, "station-a") is FileOutcome.Synced)
        val corrupted = bytes.copyOf().also { it[100] = (it[100].toInt() xor 1).toByte() }
        archive.fileFor("station-a", name).writeBytes(corrupted)
        val b = device()
        assertTrue(download(b, "station-a") is FileOutcome.Mismatch)
        assertArrayEquals(corrupted, archive.fileFor("station-a", name).readBytes())
        assertEquals(0, b.first.readCount)
    }

    @Test
    fun `corrupted resumable prefix is retried from zero in the same download`() = runBlocking {
        val crc32 = CRC32().also { it.update(bytes) }.value
        val prefix = bytes.size / 2
        archive.openPartial("station-a", name, bytes.size.toLong(), crc32).first.use { writer ->
            writer.writeAt(0, bytes.copyOfRange(0, prefix).also { it[100] = (it[100].toInt() xor 1).toByte() })
        }

        val source = device()
        val result = download(source, "station-a")

        assertTrue(result is FileOutcome.Synced)
        assertArrayEquals(bytes, archive.fileFor("station-a", name).readBytes())
        assertFalse(archive.partialRoot.walkTopDown().any { it.isFile })
        val fullWindows = (bytes.size + source.first.maxRead - 1) / source.first.maxRead
        assertTrue("a clean re-read must follow the corrupt resume", source.first.readCount > fullWindows)
        assertEquals(1, dao.upserts)
    }

    @Test
    fun `recorded hash conflict preserves bytes and provenance`() = runBlocking {
        assertTrue(download(device(), "station-a") is FileOutcome.Synced)
        val key = "station-a" to name
        val original = requireNotNull(dao.rows[key])
        dao.rows[key] = original.copy(sha256 = "invalid-recorded-fingerprint")
        val b = device()
        assertTrue(download(b, "station-a") is FileOutcome.Mismatch)
        assertEquals(0, b.first.readCount)
        assertEquals(original.sourceDevice, dao.rows[key]!!.sourceDevice)
        assertArrayEquals(bytes, archive.fileFor("station-a", name).readBytes())
    }

    @Test
    fun `commit refuses to replace an archive even if a competing writer bypasses the lease`() = runBlocking {
        assertTrue(download(device(), "station-a") is FileOutcome.Synced)
        archive.openPartial("station-a", name, bytes.size.toLong(), 0L).first.use { it.writeAt(0L, bytes) }
        try {
            archive.commit("station-a", name)
            fail("existing archive must not be replaced")
        } catch (_: AlreadyCommitted) {
            assertArrayEquals(bytes, archive.fileFor("station-a", name).readBytes())
            assertTrue(archive.partialLengths("station-a").containsKey(name))
        }
    }

    @Test
    fun aDirectoryAtTheFinalPathFailsOnlyThatFileAndKeepsTheVerifiedPartial() = runBlocking {
        assertTrue(archive.fileFor("station-a", name).mkdirs())

        val outcome = download(device(), "station-a")

        assertTrue(outcome is FileOutcome.Failed && outcome.storage)
        assertTrue(archive.fileFor("station-a", name).isDirectory)
        assertTrue(archive.partialLengths("station-a").containsKey(name))
        assertTrue(dao.rows.isEmpty())
    }

    private class InMemoryDao : ArchivedFileDao {
        val rows = ConcurrentHashMap<Pair<String, String>, ArchivedFile>()
        var upserts = 0
            private set
        private fun snapshot() = rows.values.toList()
        override fun observeAll(): Flow<List<ArchivedFile>> = flowOf(snapshot())
        override fun observeForStation(station: String): Flow<List<ArchivedFile>> = flowOf(snapshot().filter { it.station == station })
        override suspend fun forStation(station: String) = snapshot().filter { it.station == station }
        override suspend fun get(station: String, name: String) = rows[station to name]
        override fun observeCount(): Flow<Int> = flowOf(rows.size)
        override fun observeTotalBytes(): Flow<Long> = flowOf(snapshot().sumOf { it.bytes })
        override suspend fun unindexed(limit: Int) = snapshot().filter { !it.indexed }.take(limit)
        override fun observeUnindexedCount(): Flow<Int> = flowOf(snapshot().count { !it.indexed })
        override suspend fun withUtcBetween(station: String, fromNs: Long, toNs: Long) = snapshot().filter {
            it.station == station && it.indexed && it.hasUtc && (it.lastUtcNs ?: Long.MIN_VALUE) >= fromNs && (it.firstUtcNs ?: Long.MAX_VALUE) < toNs
        }
        override suspend fun indexedForStation(station: String) = snapshot().filter { it.station == station && it.indexed }
        override suspend fun stations() = snapshot().map { it.station }.distinct()
        override suspend fun upsert(file: ArchivedFile) {
            synchronized(rows) { upserts++; rows[file.station to file.name] = file }
        }
        override suspend fun delete(station: String, name: String) { rows.remove(station to name) }
    }
}
