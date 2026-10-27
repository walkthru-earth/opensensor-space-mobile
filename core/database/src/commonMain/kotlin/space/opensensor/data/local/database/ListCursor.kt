package space.opensensor.data.local.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * The saved `LIST_SINCE` cursor of one station, the 32 bytes its board sent, kept as they came.
 * Only the cursor and the audit bookkeeping, no file and no location.
 */
@Entity(tableName = "list_cursor")
class ListCursorEntity(
    @PrimaryKey val thing: String,
    val cursor: ByteArray,
    val wholeAtEpochMs: Long,
    val sinceWhole: Int,
)

@Dao
interface ListCursorDao {
    @Query("SELECT * FROM list_cursor WHERE thing = :thing")
    suspend fun get(thing: String): ListCursorEntity?

    /** The key is the whole primary key and nothing references the row, so `REPLACE` is an upsert. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(cursor: ListCursorEntity)
}
