package earth.walkthru.aqsync.data.local.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * A station generation observed on an enrolled endpoint. Historical entries never reparent files:
 * archive identity and Hive paths retain their original station. The address is the current app's
 * enrollment key, not a permanent physical-device ID or authorization for a replacement endpoint.
 * Legacy migration entries have no recorded BLE verification; they preserve existing associations.
 * No cascading foreign key: forgetting a connection must not discard archive provenance.
 */
@Entity(tableName = "station_epoch", primaryKeys = ["address", "station"])
data class StationEpoch(
    val address: String,
    val station: String,
    val deviceId: String?,
    val firstSeenEpochMs: Long,
    val observedFromAuthenticatedBle: Boolean,
)

@Dao
interface StationEpochDao {
    /** Ignore a repeat observation so the first-seen provenance is immutable. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun recordIfAbsent(epoch: StationEpoch): Long

    @Query("SELECT * FROM station_epoch WHERE address = :address ORDER BY firstSeenEpochMs, station")
    suspend fun forDevice(address: String): List<StationEpoch>

    @Query("SELECT * FROM station_epoch WHERE address = :address ORDER BY firstSeenEpochMs, station")
    fun observeForDevice(address: String): Flow<List<StationEpoch>>
}
