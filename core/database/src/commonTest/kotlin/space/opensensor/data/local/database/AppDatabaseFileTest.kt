package space.opensensor.data.local.database

import androidx.room.useReaderConnection
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import space.opensensor.archive.ArchiveRecord
import space.opensensor.archive.PrunedFile
import space.opensensor.data.archive.RoomArchiveIndex
import space.opensensor.data.archive.RoomPrunedFiles
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** A real database file on the bundled driver, on the Android host and the iOS simulator. */
class AppDatabaseFileTest {
    private val dir = newTempDir()
    private val path = "$dir/nested/${AppDatabase.FILE_NAME}"

    @AfterTest
    fun cleanUp() = deleteTempDir(dir)

    @Test
    fun rowsSurviveAReopenAtSchemaVersion14() = runTest {
        val record = ArchiveRecord("station-a", "year=2026/month=10/day=01/data.parquet", 10L, 1L, "ab", 5L, "device", utcRows = 3, lastUtcNs = 42)
        val mark = PrunedFile("station-a", "year=2026/month=10/day=02/data.parquet", 7, 8, 9)
        fileAppDatabase(path).apply {
            RoomArchiveIndex(archivedFileDao()).put(record)
            RoomPrunedFiles(prunedFileDao()).put(mark)
            close()
        }

        val again = fileAppDatabase(path)
        try {
            assertEquals(listOf(record), RoomArchiveIndex(again.archivedFileDao()).observeAll().first())
            assertEquals(listOf(mark), RoomPrunedFiles(again.prunedFileDao()).observeAll().first())
            val version = again.useReaderConnection { c -> c.usePrepared("PRAGMA user_version") { it.step(); it.getLong(0) } }
            assertEquals(14L, version)
        } finally {
            again.close()
        }
    }
}
