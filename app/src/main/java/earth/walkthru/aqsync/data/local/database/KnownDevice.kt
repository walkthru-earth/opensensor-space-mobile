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
    /**
     * LAN bearer token from `GET_TOKEN`, 32 bytes as 64 hex chars; null until fetched over BLE.
     * A secret: never log it, never show it. Schema v2.
     */
    val lanToken: String? = null,
    /** `TOKEN.port` (47390 on today's firmware). Schema v2. */
    val lanPort: Int? = null,
) {
    val hasLanToken: Boolean get() = lanToken != null

    /** Safe for logs: identity without the secret. */
    override fun toString(): String =
        "KnownDevice(address=$address, name=$name, station=$station, lanToken=${if (lanToken != null) "<set>" else "null"}, lanPort=$lanPort)"
}

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

    @Query("UPDATE known_device SET lanToken = :tokenHex, lanPort = :port WHERE address = :address")
    suspend fun setLanToken(address: String, tokenHex: String?, port: Int?)

    @Query("SELECT * FROM known_device WHERE station = :station LIMIT 1")
    suspend fun findByStation(station: String): KnownDevice?

    @Query("DELETE FROM known_device WHERE address = :address")
    suspend fun delete(address: String)
}
