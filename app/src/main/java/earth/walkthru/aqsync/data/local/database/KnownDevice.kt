package earth.walkthru.aqsync.data.local.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import earth.walkthru.aqsync.data.background.BackgroundSyncDevice
import kotlinx.coroutines.flow.Flow

/** A logger we have connected to at least once. Keyed by Bluetooth address. */
@Entity(tableName = "known_device")
data class KnownDevice(
    @PrimaryKey override val address: String,
    /** Advertised local name, `AQ-xxxx`. */
    override val name: String?,
    /** `info.station` UUID once read; null until the first successful connection. */
    override val station: String?,
    /** `info.dev`, 12 hex. */
    val deviceId: String?,
    val firmware: String?,
    val lastSeenEpochMs: Long,
    val lastConnectedEpochMs: Long?,
    /**
     * LAN bearer token from `GET_TOKEN`, stored as AES-GCM ciphertext under an Android Keystore key.
     * Rows read through the DAO carry the ciphertext, an opaque value good only for [hasLanToken]
     * and for writing the row back. Plaintext comes only from
     * [earth.walkthru.aqsync.data.local.security.LanCredentialStore], in the data layer. Writes
     * accept 64 hex chars, which the DAO encrypts. Never log it or show it. Schema v2.
     */
    val lanToken: String? = null,
    /** `TOKEN.port` (47390 on today's firmware). Schema v2. */
    val lanPort: Int? = null,
    /**
     * User opted this logger into unattended background syncs (WorkManager, ≥ 15 min apart).
     * Off by default; the switch lives on the Device screen and as a quick toggle on Files. Schema v3.
     */
    val autoSync: Boolean = false,
    /**
     * Companion Device Manager association for this address, once the user approved the system
     * dialog ("wake when nearby"): the id from `AssociationInfo.getId()` on API 33+, or
     * [COMPANION_ASSOCIATED_LEGACY] on API 31–32 where the framework only reports the address.
     * Null = never associated. The framework's own store is the truth; this column is the app's
     * memory of it so the Device screen and the presence service can skip a lookup. Schema v8.
     */
    val companionAssociationId: Int? = null,
) : BackgroundSyncDevice {
    override val hasLanToken: Boolean get() = lanToken != null
    val hasCompanion: Boolean get() = companionAssociationId != null

    /** Safe for logs: identity without the secret. */
    override fun toString(): String =
        "KnownDevice(address=$address, name=$name, station=$station, lanToken=${if (lanToken != null) "<set>" else "null"}, lanPort=$lanPort, autoSync=$autoSync, companion=$companionAssociationId)"

    companion object {
        /** API 31–32 associations have no id visible to the app; this marks "associated, id unknown". */
        const val COMPANION_ASSOCIATED_LEGACY = -1
    }
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

    @Query("UPDATE known_device SET autoSync = :enabled WHERE address = :address")
    suspend fun setAutoSync(address: String, enabled: Boolean)

    /** Devices the worker should try, most recently connected first. */
    @Query("SELECT * FROM known_device WHERE autoSync = 1 ORDER BY lastConnectedEpochMs DESC")
    suspend fun autoSyncDevices(): List<KnownDevice>

    /** Every known device for "Collect from all loggers", most recently connected first. */
    @Query("SELECT * FROM known_device ORDER BY lastConnectedEpochMs DESC")
    suspend fun allByLastConnected(): List<KnownDevice>

    @Query("SELECT COUNT(*) FROM known_device WHERE autoSync = 1")
    fun observeAutoSyncCount(): Flow<Int>

    @Query("UPDATE known_device SET companionAssociationId = :associationId WHERE address = :address")
    suspend fun setCompanionAssociation(address: String, associationId: Int?)

    /** Devices with a stored LAN token and the switch on: the only ones a Wi-Fi-arrival run can reach. */
    @Query("SELECT COUNT(*) FROM known_device WHERE autoSync = 1 AND lanToken IS NOT NULL")
    suspend fun autoSyncLanCount(): Int

    @Query("DELETE FROM known_device WHERE address = :address")
    suspend fun delete(address: String)
}
