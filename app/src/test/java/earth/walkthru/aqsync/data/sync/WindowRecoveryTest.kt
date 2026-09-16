package earth.walkthru.aqsync.data.sync

import earth.walkthru.aqsync.data.ble.DeviceSession
import earth.walkthru.aqsync.data.ble.WindowLostException
import earth.walkthru.aqsync.data.fake.FakeTransport
import earth.walkthru.aqsync.data.local.database.ArchivedFile
import earth.walkthru.aqsync.data.local.database.ArchivedFileDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The lossy-link half of the sync algorithm in docs/ble-sync-protocol.md: a `READ` window whose
 * `CHUNK`s did not all arrive is re-read from the phone's own offset, never trusted from
 * `next_offset`, and never aborts the sync. Reproduces what a phone saw on 2026-09-17 (runs of
 * leading `CHUNK`s missing from a window) with [FakeTransport.dropLeadingChunks].
 */
class WindowRecoveryTest {
    private lateinit var tmp: File
    private lateinit var fake: FakeTransport
    private lateinit var session: DeviceSession
    private lateinit var engine: SyncEngine
    private val dao = InMemoryDao()
    private val staleLog = ArrayList<String>()

    @Before
    fun setUp() = runBlocking {
        tmp = Files.createTempDirectory("aqsync-window").toFile()
        fake = FakeTransport(chunkDelayMs = 0, liveIntervalMs = 3_600_000)
        session = DeviceSession(fake).also { it.staleFrameLogger = staleLog::add }
        engine = SyncEngine(FileArchive(tmp), dao).also { it.windowRetryBackoffMs = 5 }
        withTimeout(10_000) { fake.connect() }
    }

    @After
    fun tearDown() {
        fake.close()
        tmp.deleteRecursively()
    }

    private suspend fun download(index: Int, progress: MutableList<FileProgress> = ArrayList()): FileOutcome {
        val f = fake.files[index]
        return withTimeout(30_000) {
            engine.download(session, fake.station, fake.address, f.name, f.bytes.size.toLong(), fake.maxRead) { progress += it }
        }
    }

    @Test
    fun `lost windows are re-read from the phone's offset and counted, and the file verifies`() = runBlocking {
        // File 0 is 24 000 bytes → two 16 KiB windows of 507-byte chunks.
        // READ 1 (window @0):      first 3 CHUNKs missing → gap detected at the 4th, rest of the window discarded
        // READ 2 (retry @0):       intact
        // READ 3 (window @16384):  every CHUNK missing → only READ_END arrives, next_offset ≠ bytes received
        // READ 4 (retry @16384):   2 missing again
        // READ 5 (retry @16384):   intact
        fake.dropLeadingChunks = { k -> when (k) { 1 -> 3; 3 -> 99; 4 -> 2; else -> 0 } }
        val progress = ArrayList<FileProgress>()

        val outcome = download(0, progress)

        assertTrue("expected Synced, got $outcome", outcome is FileOutcome.Synced)
        val synced = outcome as FileOutcome.Synced
        assertEquals(fake.files[0].bytes.size.toLong(), synced.bytes)
        assertEquals(5, fake.readCount)
        assertEquals(2, progress.last().windowsRecovered)
        assertTrue("progress never goes backwards", progress.zipWithNext().all { (a, b) -> b.bytesDone >= a.bytesDone })
        val onDisk = File(File(tmp, "archive/${fake.station}"), fake.files[0].name)
        assertArrayEquals(fake.files[0].bytes, onDisk.readBytes())
        assertEquals(1, dao.rows.size)
        assertEquals(synced.sha256, dao.rows.single().sha256)
        // The channel was clean between requests: nothing stale had to be dropped.
        assertTrue(staleLog.toString(), staleLog.isEmpty())
    }

    @Test
    fun `a window that keeps failing marks only that file failed and the next file still syncs`() = runBlocking {
        // 1 initial attempt + 5 retries, all lossy → give the file up; READ 7+ (next file) intact.
        fake.dropLeadingChunks = { k -> if (k <= 6) 5 else 0 }

        val first = download(0)
        assertTrue("expected Failed, got $first", first is FileOutcome.Failed)
        val failed = first as FileOutcome.Failed
        assertTrue(failed.message, failed.message.contains("lost 6×"))
        assertEquals(6, fake.readCount)
        assertTrue(dao.rows.isEmpty())

        val second = download(1)
        assertTrue("next file must sync: $second", second is FileOutcome.Synced)
        assertEquals(fake.files[1].name, dao.rows.single().name)
        val onDisk = File(File(tmp, "archive/${fake.station}"), fake.files[1].name)
        assertArrayEquals(fake.files[1].bytes, onDisk.readBytes())
    }

    @Test
    fun `session throws WindowLostException only after the window's READ_END was consumed`() = runBlocking {
        val opened = session.open(fake.files[2].name)
        fake.dropLeadingChunks = { k -> if (k == 1) 4 else 0 }
        var delivered = 0L
        try {
            session.read(opened.handle, 0, 4096) { delivered += it.payload.size }
            fail("expected WindowLostException")
        } catch (e: WindowLostException) {
            assertEquals(0L, e.receivedUpTo)
            assertEquals(0L, delivered)
            assertTrue(e.message, e.message!!.startsWith("gap in stream: expected offset 0, got CHUNK at 2028"))
        }
        // The very next READ gets its own frames, not the tail of the lost window.
        val end = session.read(opened.handle, 0, 4096) { delivered += it.payload.size }
        assertEquals(4096L, end.nextOffset)
        assertEquals(4096L, delivered)
        assertTrue(staleLog.toString(), staleLog.isEmpty())
        assertEquals(opened.handle, session.close(opened.handle).handle)
    }

    @Test
    fun `a CLOSED that arrives after the caller gave up is not taken for the LIST reply`() = runBlocking {
        val opened = session.open(fake.files[2].name)
        fake.closedDelayMs = 1_500
        val closed = withTimeoutOrNull(300) { session.close(opened.handle) }
        assertNull("close must have timed out for this test to mean anything", closed)

        // Before the fix this threw ProtocolViolationException("unexpected Closed frame in response to LIST").
        val (files, end) = withTimeout(10_000) { session.list() }
        assertEquals(fake.files.size, files.size)
        assertEquals(fake.files.size, end.count)
        assertEquals(1, staleLog.size)
        assertTrue(staleLog[0], staleLog[0].contains("after failed CLOSE") && staleLog[0].contains("terminal frame seen"))
    }

    /** Enough of the Room DAO for the engine's `upsert`. */
    private class InMemoryDao : ArchivedFileDao {
        val rows = ArrayList<ArchivedFile>()
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
    }
}
