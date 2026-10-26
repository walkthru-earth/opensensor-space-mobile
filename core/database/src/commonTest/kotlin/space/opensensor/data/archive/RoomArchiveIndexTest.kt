package space.opensensor.data.archive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import space.opensensor.archive.ArchiveKey
import space.opensensor.archive.ArchiveRecord
import space.opensensor.data.local.database.inMemoryAppDatabase
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [RoomArchiveIndex.update], [RoomArchiveIndex.putIfAbsent] and the narrow views on a real in-memory
 * Room database over the bundled SQLite driver, on the Android host and the iOS simulator.
 */
class RoomArchiveIndexTest {
    private val db = inMemoryAppDatabase()
    private val index = RoomArchiveIndex(db.archivedFileDao())

    @AfterTest
    fun close() = db.close()

    @Test
    fun concurrentUpdatesOfOneKeyLoseNoUpdate() = runTest {
        val key = ArchiveKey("11111111-2222-3333-4444-555555555555", "year=2026/month=10/day=01/data.parquet")
        index.put(ArchiveRecord(key.station, key.name, 0L, 1L, "ab", 5L, "device"))
        val writers = 64

        (1..writers).map { async(Dispatchers.Default) { index.update(key) { it.copy(bytes = it.bytes + 1) } } }.awaitAll()

        assertEquals(writers.toLong(), index.get(key)!!.bytes)
    }

    @Test
    fun putIfAbsentNeverReplacesAnExistingRecord() = runTest {
        val engine = ArchiveRecord("11111111-2222-3333-4444-555555555555", "year=2026/month=10/day=01/data.parquet", 10L, 1L, "ab", 5L, "device")
        index.put(engine)

        assertFalse(index.putIfAbsent(engine.copy(sourceDevice = "adopted", syncedAtEpochMs = 9L)))
        assertTrue(index.putIfAbsent(engine.copy(name = "year=2026/month=10/day=02/data.parquet", sourceDevice = "adopted")))

        assertEquals("device", index.get(engine.key)!!.sourceDevice)
        assertEquals(5L, index.get(engine.key)!!.syncedAtEpochMs)
        assertEquals("adopted", index.get(ArchiveKey(engine.station, "year=2026/month=10/day=02/data.parquet"))!!.sourceDevice)
    }

    @Test
    fun theNarrowViewsFollowTheTable() = runTest {
        val station = "11111111-2222-3333-4444-555555555555"
        val pending = ArchiveRecord(station, "year=2026/month=10/day=01/data.parquet", 10L, 1L, "ab", 5L, "device")
        index.put(pending)
        assertTrue(index.observeNeverIndexed().first())
        assertEquals(emptyMap<String, Long?>(), index.observeLatestTimed().first())

        index.update(pending.key) { it.copy(indexedAtEpochMs = 9L, utcRows = 3L, lastUtcNs = 42L) }

        assertFalse(index.observeNeverIndexed().first())
        assertEquals(mapOf(station to 42L), index.observeLatestTimed().first())
    }

    @Test
    fun aTimedFileWithNoEndMatchesTheSharedRulesInSql() = runTest {
        val station = "11111111-2222-3333-4444-555555555555"
        val noEnd = ArchiveRecord(station, "year=2026/month=10/day=01/data.parquet", 10L, 1L, "ab", 5L, "device", indexedAtEpochMs = 9L, utcRows = 3L, firstUtcNs = 100L)
        index.put(noEnd)

        assertEquals(mapOf<String, Long?>(station to null), index.observeLatestTimed().first())
        assertEquals(emptyList(), index.timeline(station, Long.MIN_VALUE, Long.MAX_VALUE))
        assertFalse(noEnd.onTimeline(Long.MIN_VALUE, Long.MAX_VALUE))

        val lowEnd = noEnd.copy(name = "year=2026/month=10/day=02/data.parquet", firstUtcNs = Long.MIN_VALUE, lastUtcNs = Long.MIN_VALUE)
        index.put(lowEnd)
        assertEquals(mapOf<String, Long?>(station to Long.MIN_VALUE), index.observeLatestTimed().first())
        assertEquals(listOf(lowEnd.name), index.timeline(station, Long.MIN_VALUE, Long.MIN_VALUE + 1).map { it.name })
    }

    @Test
    fun removeForgetsOnlyThatRecordAndStationsAreDistinct() = runTest {
        val a = ArchiveRecord("station-a", "year=2026/month=10/day=01/data.parquet", 10L, 1L, "ab", 5L, "device")
        val b = a.copy(name = "year=2026/month=10/day=02/data.parquet")
        val c = a.copy(station = "station-b")
        listOf(a, b, c).forEach { index.put(it) }

        index.remove(a.key)

        assertEquals(listOf(b), index.observeStation("station-a").first())
        assertEquals(setOf("station-a", "station-b"), index.stations().toSet())
        assertEquals(listOf(b.name, c.name).sorted(), index.observeAll().first().map { it.name }.sorted())
    }
}
