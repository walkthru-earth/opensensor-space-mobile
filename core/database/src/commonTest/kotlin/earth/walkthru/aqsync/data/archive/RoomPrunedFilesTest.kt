package earth.walkthru.aqsync.data.archive

import earth.walkthru.aqsync.archive.PrunedFile
import earth.walkthru.aqsync.data.local.database.inMemoryAppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** [RoomPrunedFiles] over the `pruned_file` table, on the Android host and the iOS simulator. */
class RoomPrunedFilesTest {
    private val db = inMemoryAppDatabase()
    private val pruned = RoomPrunedFiles(db.prunedFileDao())
    private val a = PrunedFile("station-a", "year=2026/month=10/day=01/data.parquet", 100, 4_294_967_295, 1)
    private val b = PrunedFile("station-b", "year=2026/month=10/day=01/data.parquet", 0, 0, 2)

    @AfterTest
    fun close() = db.close()

    @Test
    fun putReplacesTheRecordOfItsKeyAndListsInStationOrder() = runTest {
        pruned.put(b)
        pruned.put(a)
        pruned.put(a.copy(bytes = 200))

        assertEquals(listOf(a.copy(bytes = 200), b), pruned.observeAll().first())
        assertEquals(listOf(b), pruned.forStation("station-b"))
    }

    @Test
    fun removeAndClear() = runTest {
        pruned.put(a)
        pruned.put(b)

        pruned.remove(a.key)
        assertEquals(listOf(b), pruned.observeAll().first())
        assertEquals(1, pruned.clear())
        assertEquals(emptyList(), pruned.observeAll().first())
    }
}
