package space.opensensor.data.archive

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import space.opensensor.archive.PrunedFile
import space.opensensor.data.local.database.inMemoryAppDatabase
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** [RoomPrunedFiles] over the `pruned_file` table, on the Android host and the iOS simulator. */
class RoomPrunedFilesTest {
    private val db = inMemoryAppDatabase()
    private val pruned = RoomPrunedFiles(db.prunedFileDao())
    private val a = PrunedFile("thing-a", "year=2026/month=10/day=01/data.parquet", 100, 4_294_967_295, 1)
    private val b = PrunedFile("thing-b", "year=2026/month=10/day=01/data.parquet", 0, 0, 2)

    @AfterTest
    fun close() = db.close()

    @Test
    fun putReplacesTheRecordOfItsKeyAndListsInThingOrder() = runTest {
        pruned.put(b)
        pruned.put(a)
        pruned.put(a.copy(bytes = 200))

        assertEquals(listOf(a.copy(bytes = 200), b), pruned.observeAll().first())
        assertEquals(listOf(b), pruned.forThing("thing-b"))
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
