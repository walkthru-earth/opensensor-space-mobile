package earth.walkthru.aqsync.data.local.database

import android.content.ContextWrapper
import androidx.room.Room
import androidx.room.execSQL
import androidx.room.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.cash.turbine.test
import earth.walkthru.aqsync.data.ManifestState
import earth.walkthru.aqsync.data.SyncProgress
import earth.walkthru.aqsync.data.archive.toRecord
import earth.walkthru.aqsync.data.planWithProgress
import earth.walkthru.aqsync.data.protocol.DeviceInfo
import earth.walkthru.aqsync.data.protocol.Frame
import earth.walkthru.aqsync.data.sync.FileSyncState
import earth.walkthru.aqsync.data.sync.SyncPlanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
        utcRows: Long? = null,
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
        utcRows = utcRows,
    )

    @Test
    fun `migration 10 to 11 reopens only indexed rows without UTC rows or an error`() = runBlocking {
        dao.upsert(file("a", "zero.parquet", indexedAt = 5, utcRows = 0))
        dao.upsert(file("a", "null.parquet", indexedAt = 5, utcRows = null))
        dao.upsert(file("a", "dated.parquet", indexedAt = 5, utcRows = 90))
        dao.upsert(file("a", "failed.parquet", indexedAt = 5, indexError = "bad footer", utcRows = 0))
        assertEquals(listOf("failed.parquet"), dao.unindexed(10).map { it.name })

        db.useWriterConnection { it.execSQL(AppDatabase.MIGRATION_10_11_SQL) }

        // A row with an error stays in the retry queue it was already in, never reopened by the migration.
        assertEquals(setOf("zero.parquet", "null.parquet", "failed.parquet"), dao.unindexed(10).map { it.name }.toSet())
        assertEquals(null, dao.get("a", "zero.parquet")!!.indexedAtEpochMs)
        assertEquals(null, dao.get("a", "null.parquet")!!.indexedAtEpochMs)
        val dated = dao.get("a", "dated.parquet")!!
        assertEquals(5L, dated.indexedAtEpochMs)
        assertEquals(null, dated.indexError)
        val failed = dao.get("a", "failed.parquet")!!
        assertEquals(5L, failed.indexedAtEpochMs)
        assertEquals("bad footer", failed.indexError)
        assertTrue(AppDatabase.MIGRATION_10_11 in AppDatabase.MIGRATIONS)
        assertEquals(13, AppDatabase.MIGRATIONS.maxOf { it.endVersion })
    }

    @Test
    fun `the timeline query reads indexed rows only, the same test as onTimeline`() = runBlocking {
        fun spanned(name: String, indexedAt: Long?, indexError: String? = null) =
            file("a", name, indexedAt = indexedAt, indexError = indexError, lastUtcNs = 200, utcRows = 3).copy(firstUtcNs = 100, purpose = "telemetry")
        // A NULL start with a real end stays out, for real UTC rows and for an estimated span alike.
        val noStart = spanned("nostart.parquet", 5).copy(firstUtcNs = null)
        val noEstStart = file("a", "noeststart.parquet", indexedAt = 5, utcRows = 0).copy(estFirstUtcNs = null, estLastUtcNs = 200, purpose = "telemetry")
        val rows = listOf(spanned("ok.parquet", 5), spanned("reopened.parquet", null), spanned("failed.parquet", 5, "bad"), noStart, noEstStart)
        rows.forEach { dao.upsert(it) }

        assertEquals(listOf("ok.parquet"), dao.withUtcBetween("a", 0, 1000).map { it.name })
        assertEquals(rows.filter { it.toRecord().onTimeline(0, 1000) }.map { it.name }, dao.withUtcBetween("a", 0, 1000).map { it.name })
    }

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

    @Test
    fun `index writes and other stations do not rebuild the projected plan`(): Unit = runBlocking {
        val station = "a"
        val name = "station=$station/year=2026/month=10/day=11/data_0900.parquet"
        val original = file(station, name, bytes = 100)
        dao.upsert(original)
        val projections = Channel<List<ArchivedFileIdentity>>(Channel.UNLIMITED)
        var plans = 0
        var partialReads = 0
        val manifest = ManifestState.Loaded(listOf(Frame.File(100, name)), Frame.ListEnd(1, 0, 0, 0), 0)
        val plan = planWithProgress(
            manifest = MutableStateFlow(manifest),
            info = MutableStateFlow(DeviceInfo(station = station)),
            archived = { dao.observeIdentitiesForStation(it).onEach { rows -> projections.send(rows) } },
            sync = MutableStateFlow(SyncProgress()),
            partialLengths = { partialReads++; emptyMap() },
            planner = { s, files, identities, partials, pruned -> plans++; SyncPlanner.plan(s, files, identities, partials, pruned) },
        )
        plan.test {
            assertEquals(FileSyncState.SYNCED, awaitItem()!!.entries.single().state)
            val identity = withTimeout(10_000) { projections.receive() }

            dao.update(station, name) { it.copy(indexedAtEpochMs = 10, rows = 3, utcRows = 3, firstUtcNs = 100, lastUtcNs = 300) }
            assertEquals(identity, withTimeout(10_000) { projections.receive() })
            dao.upsert(file("b", "b.parquet"))
            assertEquals(identity, withTimeout(10_000) { projections.receive() })

            // A real identity change is the barrier after the two unchanged query results.
            dao.update(station, name) { it.copy(bytes = 200) }
            assertEquals(FileSyncState.MISMATCH, awaitItem()!!.entries.single().state)
            assertEquals(2, plans)
            assertEquals(2, partialReads)
            cancelAndIgnoreRemainingEvents()
        }
        projections.close()
    }

    @Test
    fun `never indexed flag excludes completed indexing failures`() = runBlocking {
        dao.observeAnyNeverIndexed().test {
            assertFalse(awaitItem())
            dao.upsert(file("a", "new.parquet"))
            assertTrue(awaitItem())
            dao.update("a", "new.parquet") { it.copy(indexedAtEpochMs = 10, indexError = "bad footer") }
            assertFalse(awaitItem())
            assertEquals(1, dao.observeUnindexedCount().first())
            dao.upsert(file("b", "new.parquet"))
            assertTrue(awaitItem())
            dao.delete("b", "new.parquet")
            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `latest timed ends use eligible real and estimated rows only`() = runBlocking {
        val real = file("a", "real.parquet", indexedAt = 1, lastUtcNs = 100).copy(utcRows = 1, purpose = "telemetry")
        val estimated = file("a", "estimated.parquet", indexedAt = 1).copy(utcRows = 0, estFirstUtcNs = 150, estLastUtcNs = 200)
        dao.upsert(real)
        dao.upsert(estimated)
        dao.upsert(real.copy(name = "benchmark.parquet", purpose = "codec-comparison-duplicate-rows", lastUtcNs = 1_000))
        dao.upsert(real.copy(name = "failed.parquet", indexError = "bad footer", lastUtcNs = 2_000))
        dao.upsert(real.copy(name = "unindexed.parquet", indexedAtEpochMs = null, lastUtcNs = 3_000))
        dao.upsert(estimated.copy(name = "incomplete-estimate.parquet", estFirstUtcNs = null, estLastUtcNs = 4_000))
        // A real UTC row takes precedence over its stale estimate.
        dao.upsert(real.copy(name = "real-with-estimate.parquet", estFirstUtcNs = 5_000, estLastUtcNs = 6_000))
        dao.upsert(real.copy(station = "b", name = "no-end.parquet", lastUtcNs = null))
        dao.upsert(file("untimed", "untimed.parquet", indexedAt = 1))

        val ends = dao.observeLatestTimed().first().associate { it.station to it.lastNs }
        assertEquals(mapOf("a" to 200L, "b" to null), ends)
        dao.delete("a", "estimated.parquet")
        assertEquals(100L, dao.observeLatestTimed().first().single { it.station == "a" }.lastNs)
    }
}
