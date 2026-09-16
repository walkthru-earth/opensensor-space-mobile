package earth.walkthru.aqsync.data.local.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** A logger we have connected to at least once. Keyed by Bluetooth address. */
@Entity(tableName = "known_device")
data class KnownDevice(
    @PrimaryKey val address: String,
    /** Advertised local name, `AQ-xxxx`. */
    val name: String?,
    /** `info.station` UUID once read; null until the first successful connection. */
    val station: String?,
    /** `info.dev`, 12 hex. */
    val deviceId: String?,
    val firmware: String?,
    val lastSeenEpochMs: Long,
    val lastConnectedEpochMs: Long?,
)

@Dao
interface KnownDeviceDao {
    @Query("SELECT * FROM known_device ORDER BY lastSeenEpochMs DESC")
    fun observeAll(): Flow<List<KnownDevice>>

    @Query("SELECT * FROM known_device WHERE address = :address")
    suspend fun get(address: String): KnownDevice?

    @Upsert
    suspend fun upsert(device: KnownDevice)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(device: KnownDevice): Long

    @Query("UPDATE known_device SET lastSeenEpochMs = :epochMs, name = COALESCE(:name, name) WHERE address = :address")
    suspend fun touch(address: String, name: String?, epochMs: Long)

    @Query("DELETE FROM known_device WHERE address = :address")
    suspend fun delete(address: String)
}
