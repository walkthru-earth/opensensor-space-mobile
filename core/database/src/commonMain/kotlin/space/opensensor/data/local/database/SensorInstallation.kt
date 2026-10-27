package space.opensensor.data.local.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Phone-local record of which replaceable sensor module was fitted to a logger and when. */
@Entity(tableName = "sensor_installation", indices = [Index("address"), Index("thing")])
data class SensorInstallation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val address: String,
    val thing: String,
    val deviceId: String?,
    val vendor: String,
    val model: String,
    val serial: String?,
    val batch: String?,
    /** Phone UTC; this records the user's assertion time, not a retroactive sensor swap date. */
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long? = null,
    val source: String,
)

@Dao
interface SensorInstallationDao {
    @Query("SELECT * FROM sensor_installation WHERE address = :address AND endedAtEpochMs IS NULL ORDER BY id DESC LIMIT 1")
    fun observeCurrent(address: String): Flow<SensorInstallation?>

    @Query("SELECT * FROM sensor_installation WHERE address = :address ORDER BY startedAtEpochMs DESC, id DESC")
    fun observeHistory(address: String): Flow<List<SensorInstallation>>

    @Query("SELECT * FROM sensor_installation WHERE address = :address AND endedAtEpochMs IS NULL ORDER BY id DESC LIMIT 1")
    suspend fun current(address: String): SensorInstallation?

    @Query("UPDATE sensor_installation SET endedAtEpochMs = :atEpochMs WHERE address = :address AND endedAtEpochMs IS NULL")
    suspend fun closeCurrent(address: String, atEpochMs: Long)

    @Insert
    suspend fun insert(installation: SensorInstallation)

    @Transaction
    suspend fun record(installation: SensorInstallation) {
        val old = current(installation.address)
        if (old?.thing == installation.thing && old.serial == installation.serial &&
            old.model == installation.model && old.vendor == installation.vendor
        ) {
            return
        }
        closeCurrent(installation.address, installation.startedAtEpochMs)
        insert(installation)
    }
}
