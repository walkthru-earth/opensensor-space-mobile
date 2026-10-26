package space.opensensor.data.local.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * A thing generation observed on an enrolled endpoint. Historical entries never reparent files:
 * archive identity and Hive paths retain their original thing. The address is the current app's
 * enrollment key, not a permanent physical-device ID or authorization for a replacement endpoint.
 * Legacy migration entries have no recorded BLE verification; they preserve existing associations.
 * No cascading foreign key: forgetting a connection must not discard archive provenance.
 */
@Entity(tableName = "thing_epoch", primaryKeys = ["address", "thing"])
data class ThingEpoch(
    val address: String,
    val thing: String,
    val deviceId: String?,
    val firstSeenEpochMs: Long,
    val observedFromAuthenticatedBle: Boolean,
)

@Dao
interface ThingEpochDao {
    /** Ignore a repeat observation so the first-seen provenance is immutable. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun recordIfAbsent(epoch: ThingEpoch): Long

    @Query("SELECT * FROM thing_epoch WHERE address = :address ORDER BY firstSeenEpochMs, thing")
    suspend fun forDevice(address: String): List<ThingEpoch>

    @Query("SELECT * FROM thing_epoch WHERE address = :address ORDER BY firstSeenEpochMs, thing")
    fun observeForDevice(address: String): Flow<List<ThingEpoch>>
}
