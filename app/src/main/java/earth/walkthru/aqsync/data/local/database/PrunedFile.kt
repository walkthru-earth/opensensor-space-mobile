package earth.walkthru.aqsync.data.local.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** A sensor file the user removed from this phone in Settings. Sync skips it while the device lists the same size. */
@Entity(tableName = "pruned_file", primaryKeys = ["station", "name"])
data class PrunedFileEntity(
    val station: String,
    val name: String,
    val bytes: Long,
    val crc32: Long,
    val prunedAtEpochMs: Long,
)

@Dao
interface PrunedFileDao {
    @Query("SELECT * FROM pruned_file ORDER BY station, name")
    fun observeAll(): Flow<List<PrunedFileEntity>>

    @Query("SELECT * FROM pruned_file WHERE station = :station")
    suspend fun forStation(station: String): List<PrunedFileEntity>

    @Upsert
    suspend fun upsert(file: PrunedFileEntity)

    @Query("DELETE FROM pruned_file WHERE station = :station AND name = :name")
    suspend fun delete(station: String, name: String)

    @Query("DELETE FROM pruned_file")
    suspend fun clear(): Int
}
