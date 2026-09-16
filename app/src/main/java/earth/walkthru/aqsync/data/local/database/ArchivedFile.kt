package earth.walkthru.aqsync.data.local.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * One verified Parquet file in the local archive. Identity is `(station, name)`; the file is
 * immutable, so `bytes` and `crc32` are consistency checks against the device manifest.
 *
 * A row exists only after the file passed size + CRC-32 + `PAR1` verification and was renamed
 * into place: a partial or mismatched download never gets a row.
 */
@Entity(
    tableName = "archived_file",
    indices = [Index(value = ["station", "name"], unique = true)],
)
data class ArchivedFile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val station: String,
    /** Relative Hive path exactly as the device reported it. */
    val name: String,
    val bytes: Long,
    /** CRC-32 (IEEE) as an unsigned 32-bit value. */
    val crc32: Long,
    val sha256: String,
    val syncedAtEpochMs: Long,
    /** Bluetooth address (or fake id) of the device the copy came from. */
    val sourceDevice: String,
)

@Dao
interface ArchivedFileDao {
    @Query("SELECT * FROM archived_file ORDER BY name")
    fun observeAll(): Flow<List<ArchivedFile>>

    @Query("SELECT * FROM archived_file WHERE station = :station ORDER BY name")
    fun observeForStation(station: String): Flow<List<ArchivedFile>>

    @Query("SELECT * FROM archived_file WHERE station = :station")
    suspend fun forStation(station: String): List<ArchivedFile>

    @Query("SELECT * FROM archived_file WHERE station = :station AND name = :name")
    suspend fun get(station: String, name: String): ArchivedFile?

    @Query("SELECT COUNT(*) FROM archived_file")
    fun observeCount(): Flow<Int>

    @Query("SELECT COALESCE(SUM(bytes), 0) FROM archived_file")
    fun observeTotalBytes(): Flow<Long>

    @Upsert
    suspend fun upsert(file: ArchivedFile)

    @Query("DELETE FROM archived_file WHERE station = :station AND name = :name")
    suspend fun delete(station: String, name: String)
}
