package space.opensensor.data.local.database

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * The H3 cell of one of the user's own fixed Things, keyed by its UUID (#226). Discover draws it as
 * a pin. Never a coordinate. A moving Thing has no row. No foreign key, so the Discover host joins
 * it with the Things the phone still knows.
 */
@Entity(tableName = "thing_place")
class ThingPlaceEntity(
    @PrimaryKey @ColumnInfo(name = "thing_uuid") val thing: String,
    val cell: String,
    @ColumnInfo(name = "updated_epoch_ms") val updatedEpochMs: Long,
)

@Dao
interface ThingPlaceDao {
    @Query("SELECT * FROM thing_place ORDER BY thing_uuid")
    fun observeAll(): Flow<List<ThingPlaceEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(place: ThingPlaceEntity)

    @Query("DELETE FROM thing_place WHERE thing_uuid = :thing")
    suspend fun remove(thing: String)
}
