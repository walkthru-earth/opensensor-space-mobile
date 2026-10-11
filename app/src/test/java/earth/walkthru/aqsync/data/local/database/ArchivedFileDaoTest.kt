package earth.walkthru.aqsync.data.local.database

import android.content.ContextWrapper
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The station-scoped, projected and aggregate queries of [ArchivedFileDao] on an in-memory Room database (#63). */
class ArchivedFileDaoTest {
    private val db = Room.inMemoryDatabaseBuilder(ContextWrapper(null), AppDatabase::class.java)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
    private val dao = db.archivedFileDao()

    @After
    fun close() = db.close()

    private fun file(
        station: String,
        name: String,
        bytes: Long = 100,
        crc32: Long = 1,
        syncedAt: Long = 0,
        indexedAt: Long? = null,
        indexError: String? = null,
        lastUtcNs: Long? = null,
    ) = ArchivedFile(
        station = station,
        name = name,
        bytes = bytes,
        crc32 = crc32,
        sha256 = "verified",
        syncedAtEpochMs = syncedAt,
        sourceDevice = station,
        indexedAtEpochMs = indexedAt,
        indexError = indexError,
        lastUtcNs = lastUtcNs,
    )

    @Test
    fun `identities hold the station's rows only, ordered by name`() = runBlocking {
        dao.upsert(file("a", "b.parquet", bytes = 20, crc32 = 2))
        dao.upsert(file("a", "a.parquet", bytes = 10, crc32 = 1))
        dao.upsert(file("b", "c.parquet"))

        val expected = listOf(ArchivedFileIdentity("a", "a.parquet", 10, 1), ArchivedFileIdentity("a", "b.parquet", 20, 2))
        assertEquals(expected, dao.identitiesForStation("a"))
        assertEquals(expected, dao.observeIdentitiesForStation("a").first())
        assertEquals(emptyList<ArchivedFileIdentity>(), dao.identitiesForStation("none"))
    }

    @Test
    fun `observed identities follow writes to the station`() = runBlocking {
        dao.observeIdentitiesForStation("a").test {
            assertEquals(emptyList<ArchivedFileIdentity>(), awaitItem())
            dao.upsert(file("a", "a.parquet", bytes = 10, crc32 = 1))
            assertEquals(listOf(ArchivedFileIdentity("a", "a.parquet", 10, 1)), awaitItem())
            dao.delete("a", "a.parquet")
            assertEquals(emptyList<ArchivedFileIdentity>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `count and exists are per station`() = runBlocking {
        assertEquals(0, dao.observeCountForStation("a").first())
        assertFalse(dao.observeAnyForStation("a").first())

        dao.upsert(file("a", "1.parquet"))
        dao.upsert(file("a", "2.parquet"))
        dao.upsert(file("b", "3.parquet"))

        assertEquals(2, dao.observeCountForStation("a").first())
        assertEquals(1, dao.observeCountForStation("b").first())
        assertTrue(dao.observeAnyForStation("a").first())
        assertFalse(dao.observeAnyForStation("c").first())
    }

    @Test
    fun `unindexed count is per station and counts failed rows`() = runBlocking {
        dao.upsert(file("a", "new.parquet"))
        dao.upsert(file("a", "failed.parquet", indexedAt = 5, indexError = "bad footer"))
        dao.upsert(file("a", "done.parquet", indexedAt = 5))
        dao.upsert(file("b", "other.parquet"))

        assertEquals(2, dao.observeUnindexedCountForStation("a").first())
        assertEquals(1, dao.observeUnindexedCountForStation("b").first())
        assertEquals(0, dao.observeUnindexedCountForStation("c").first())
        assertEquals(3, dao.observeUnindexedCount().first())
    }

    @Test
    fun `station glances aggregate count, last sync and newest data`() = runBlocking {
        assertEquals(emptyList<StationArchiveGlance>(), dao.observeStationGlances().first())

        dao.upsert(file("a", "1.parquet", syncedAt = 10, lastUtcNs = 100))
        dao.upsert(file("a", "2.parquet", syncedAt = 30, lastUtcNs = null))
        dao.upsert(file("a", "3.parquet", syncedAt = 20, lastUtcNs = 300))
        dao.upsert(file("b", "4.parquet", syncedAt = 5))

        val glances = dao.observeStationGlances().first().associateBy { it.station }
        assertEquals(StationArchiveGlance("a", files = 3, lastSyncedMs = 30, lastDataUtcNs = 300), glances["a"])
        // A station whose files have no UTC rows keeps a null newest time, as the Kotlin fold did.
        assertEquals(StationArchiveGlance("b", files = 1, lastSyncedMs = 5, lastDataUtcNs = null), glances["b"])
        assertEquals(2, glances.size)
    }
}
