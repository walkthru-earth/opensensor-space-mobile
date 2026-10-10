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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** [RoomArchiveIndex.update] on a real in-memory Room database over the bundled SQLite driver. */
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
        val key = ArchiveKey("53315f5f-cb85-4d8d-b623-d56266084189", "year=2026/month=10/day=01/data.parquet")
        index.put(ArchiveRecord(key.station, key.name, 0L, 1L, "ab", 5L, "device"))
        val writers = 64

        (1..writers).map { async(Dispatchers.Default) { index.update(key) { it.copy(bytes = it.bytes + 1) } } }.awaitAll()

        assertEquals(writers.toLong(), index.get(key)!!.bytes)
    }
}
