package earth.walkthru.aqsync.data.archive

import android.content.ContextWrapper
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import earth.walkthru.aqsync.archive.ArchiveKey
import earth.walkthru.aqsync.archive.ArchiveRecord
import earth.walkthru.aqsync.data.local.database.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [RoomArchiveIndex.update], [RoomArchiveIndex.putIfAbsent] and the narrow views on a real in-memory Room database over the bundled SQLite driver. */
class RoomArchiveIndexTest {
    private val db = Room.inMemoryDatabaseBuilder(ContextWrapper(null), AppDatabase::class.java)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
    private val index = RoomArchiveIndex(db.archivedFileDao())

    @After
    fun close() = db.close()

    @Test
    fun `concurrent updates of one key lose no update`() = runBlocking {
        val key = ArchiveKey("11111111-2222-3333-4444-555555555555", "year=2026/month=10/day=01/data.parquet")
        index.put(ArchiveRecord(key.station, key.name, 0L, 1L, "ab", 5L, "device"))
        val writers = 64

        (1..writers).map { async(Dispatchers.Default) { index.update(key) { it.copy(bytes = it.bytes + 1) } } }.awaitAll()

        assertEquals(writers.toLong(), index.get(key)!!.bytes)
    }

    @Test
    fun `putIfAbsent never replaces an existing record`() = runBlocking {
        val engine = ArchiveRecord("11111111-2222-3333-4444-555555555555", "year=2026/month=10/day=01/data.parquet", 10L, 1L, "ab", 5L, "device")
        index.put(engine)

        assertFalse(index.putIfAbsent(engine.copy(sourceDevice = "adopted", syncedAtEpochMs = 9L)))
        assertTrue(index.putIfAbsent(engine.copy(name = "year=2026/month=10/day=02/data.parquet", sourceDevice = "adopted")))

        assertEquals("device", index.get(engine.key)!!.sourceDevice)
        assertEquals(5L, index.get(engine.key)!!.syncedAtEpochMs)
        assertEquals("adopted", index.get(ArchiveKey(engine.station, "year=2026/month=10/day=02/data.parquet"))!!.sourceDevice)
    }

    @Test
    fun `the narrow views follow the table`() = runBlocking {
        val station = "11111111-2222-3333-4444-555555555555"
        val pending = ArchiveRecord(station, "year=2026/month=10/day=01/data.parquet", 10L, 1L, "ab", 5L, "device")
        index.put(pending)
        assertTrue(index.observeNeverIndexed().first())
        assertEquals(emptyMap<String, Long>(), index.observeLatestTimed().first())

        index.update(pending.key) { it.copy(indexedAtEpochMs = 9L, utcRows = 3L, lastUtcNs = 42L) }

        assertFalse(index.observeNeverIndexed().first())
        assertEquals(mapOf(station to 42L), index.observeLatestTimed().first())
    }
}
