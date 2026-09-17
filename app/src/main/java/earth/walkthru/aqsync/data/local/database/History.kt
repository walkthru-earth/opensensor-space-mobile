package earth.walkthru.aqsync.data.local.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Aggregate tier row (schema v5). `resolution` is `Resolution.seconds` (900 / 3600 / 86400); identity
 * is `(station, resolution, startEpochS)`. Rebuilt per UTC day by `HistoryDeriver` from the verified
 * files, so it is a cache of the archive, never a source: deleting every row and re-deriving yields
 * the same table.
 */
@Entity(
    tableName = "aggregate_bucket",
    indices = [Index(value = ["station", "resolution", "startEpochS"], unique = true)],
)
data class AggregateBucket(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val station: String,
    val resolution: Long,
    val startEpochS: Long,
    val rows: Int,
    val validRows: Int,
    val pm1Mean: Float?,
    val pm25Mean: Float?,
    val pm25Min: Int?,
    val pm25Max: Int?,
    val pm10Mean: Float?,
    val particlesGt03Mean: Float?,
    val particlesGt25Mean: Float?,
    val boardTempMean: Float?,
    val batteryPercentMean: Float?,
    val vbusMvMean: Float?,
)

/**
 * Bookkeeping for one station-day of derived data: which verified files went in (a signature over
 * their names and CRCs), so a day is rebuilt exactly when its source set changes — a late file from
 * a logger that was offline, a re-synced mismatch — and never otherwise.
 */
@Entity(
    tableName = "derived_day",
    indices = [Index(value = ["station", "epochDay"], unique = true)],
)
data class DerivedDay(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val station: String,
    /** UTC day number (epoch seconds / 86400). */
    val epochDay: Long,
    /** SHA-256 hex over the sorted `name:crc32` list of source files. */
    val sourceSignature: String,
    val sourceFiles: Int,
    val rows: Int,
    val builtAtEpochMs: Long,
    /** Path of the derived daily Parquet file relative to the derived root, once the writer exists; null until then. */
    val dailyFile: String? = null,
)

@Dao
interface AggregateBucketDao {
    @Query(
        "SELECT * FROM aggregate_bucket WHERE station = :station AND resolution = :resolution " +
            "AND startEpochS >= :fromS AND startEpochS < :toS ORDER BY startEpochS"
    )
    fun observe(station: String, resolution: Long, fromS: Long, toS: Long): Flow<List<AggregateBucket>>

    @Query(
        "SELECT * FROM aggregate_bucket WHERE station = :station AND resolution = :resolution " +
            "AND startEpochS >= :fromS AND startEpochS < :toS ORDER BY startEpochS"
    )
    suspend fun between(station: String, resolution: Long, fromS: Long, toS: Long): List<AggregateBucket>

    @Query("SELECT MIN(startEpochS) FROM aggregate_bucket WHERE station = :station AND resolution = 86400")
    fun observeFirstDay(station: String): Flow<Long?>

    @Query("SELECT MAX(startEpochS) FROM aggregate_bucket WHERE station = :station AND resolution = 86400")
    fun observeLastDay(station: String): Flow<Long?>

    @Query("SELECT COUNT(*) FROM aggregate_bucket WHERE station = :station AND resolution = 86400")
    fun observeDayCount(station: String): Flow<Int>

    @Query("DELETE FROM aggregate_bucket WHERE station = :station AND startEpochS >= :fromS AND startEpochS < :toS")
    suspend fun deleteBetween(station: String, fromS: Long, toS: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<AggregateBucket>)

    /** Replace one station-day atomically: History never sees a half-rebuilt day. */
    @Transaction
    suspend fun replaceDay(station: String, dayStartS: Long, rows: List<AggregateBucket>) {
        deleteBetween(station, dayStartS, dayStartS + 86_400)
        if (rows.isNotEmpty()) insertAll(rows)
    }
}

@Dao
interface DerivedDayDao {
    @Query("SELECT * FROM derived_day WHERE station = :station")
    suspend fun forStation(station: String): List<DerivedDay>

    @Query("SELECT * FROM derived_day WHERE station = :station AND epochDay = :epochDay")
    suspend fun get(station: String, epochDay: Long): DerivedDay?

    @Upsert
    suspend fun upsert(day: DerivedDay)

    @Query("DELETE FROM derived_day WHERE station = :station AND epochDay = :epochDay")
    suspend fun delete(station: String, epochDay: Long)

    @Query("SELECT COUNT(*) FROM derived_day")
    fun observeCount(): Flow<Int>

    @Query("SELECT DISTINCT station FROM derived_day")
    suspend fun stations(): List<String>
}
