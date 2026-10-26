package space.opensensor.data.local.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Aggregate tier row (schema v7), **one row per metric**: identity `(thing, metricId, resolution,
 * startEpochS)`. `metricId` is `MetricSpec.id`, the template column name, so a new sensor is a new
 * template datastream and nothing here changes. Rebuilt per UTC day by `HistoryDeriver` from the verified files —
 * a cache of the archive, never a source: deleting every row and re-deriving yields the same table.
 */
@Entity(
    tableName = "metric_bucket",
    indices = [Index(value = ["thing", "metricId", "resolution", "startEpochS"], unique = true)],
)
data class MetricBucket(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val thing: String,
    val metricId: String,
    /** `Resolution.seconds`: 900 / 3600 / 86400. */
    val resolution: Long,
    val startEpochS: Long,
    /** Every timed row in the slot (same for all metrics of the slot). */
    val rows: Int,
    /** Rows that passed this metric's gate and had a value. */
    val validRows: Int,
    /** Of `rows`, those on estimated time (§4.6). */
    val estimatedRows: Int,
    /** The metric's aggregate of the valid rows (a mean, a total, the latest value) and their extremes. */
    val mean: Float,
    val min: Float,
    val max: Float,
    /** The bucket's footer `acquisition.sample_interval_ms`, 0 when unknown. Coverage divides by the rows it allows. */
    val sampleIntervalMs: Long,
)

/**
 * Bookkeeping for one thing-day of derived data: which verified files went in (a signature over
 * their names and CRCs), so a day is rebuilt exactly when its source set changes — a late file from
 * a logger that was offline, a re-synced mismatch — and never otherwise.
 */
@Entity(
    tableName = "derived_day",
    indices = [Index(value = ["thing", "epochDay"], unique = true)],
)
data class DerivedDay(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val thing: String,
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
interface MetricBucketDao {
    @Query(
        "SELECT * FROM metric_bucket WHERE thing = :thing AND metricId = :metricId AND resolution = :resolution " +
            "AND startEpochS >= :fromS AND startEpochS < :toS ORDER BY startEpochS"
    )
    fun observe(thing: String, metricId: String, resolution: Long, fromS: Long, toS: Long): Flow<List<MetricBucket>>

    @Query("SELECT MIN(startEpochS) FROM metric_bucket WHERE thing = :thing AND resolution = 86400")
    fun observeFirstDay(thing: String): Flow<Long?>

    @Query("SELECT MAX(startEpochS) FROM metric_bucket WHERE thing = :thing AND resolution = 86400")
    fun observeLastDay(thing: String): Flow<Long?>

    @Query("SELECT COUNT(DISTINCT startEpochS) FROM metric_bucket WHERE thing = :thing AND resolution = 86400")
    fun observeDayCount(thing: String): Flow<Int>

    /** Every thing's day buckets of one metric, newest first. Home picks the latest per thing. */
    @Query("SELECT * FROM metric_bucket WHERE metricId = :metricId AND resolution = 86400 ORDER BY startEpochS DESC")
    fun observeAllDays(metricId: String): Flow<List<MetricBucket>>

    @Query("DELETE FROM metric_bucket WHERE thing = :thing AND startEpochS >= :fromS AND startEpochS < :toS")
    suspend fun deleteBetween(thing: String, fromS: Long, toS: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<MetricBucket>)

    /** Replace one thing-day atomically (every metric, every resolution): History never sees a half-rebuilt day. */
    @Transaction
    suspend fun replaceDay(thing: String, dayStartS: Long, rows: List<MetricBucket>) {
        deleteBetween(thing, dayStartS, dayStartS + 86_400)
        if (rows.isNotEmpty()) insertAll(rows)
    }
}

@Dao
interface DerivedDayDao {
    @Query("SELECT * FROM derived_day WHERE thing = :thing")
    suspend fun forThing(thing: String): List<DerivedDay>

    @Query("SELECT * FROM derived_day WHERE thing = :thing AND epochDay = :epochDay")
    suspend fun get(thing: String, epochDay: Long): DerivedDay?

    /**
     * `REPLACE`, not `@Upsert`: the entity has an auto-generated id **and** a unique `(thing, epochDay)`
     * index. Room's `@Upsert` resolves the unique-index conflict with `UPDATE … WHERE id = ?` using the
     * entity's id — 0 for a freshly built row — which updates nothing, so a day's signature was never
     * refreshed after its first build and every derive pass rebuilt every day (found on the phone,
     * 2026-09-18: "derived 3 of 3 thing-days" on each pass). `REPLACE` deletes the conflicting row
     * and inserts the new one; the id carries no meaning here.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(day: DerivedDay)

    @Query("DELETE FROM derived_day WHERE thing = :thing AND epochDay = :epochDay")
    suspend fun delete(thing: String, epochDay: Long)

    @Query("SELECT COUNT(*) FROM derived_day")
    fun observeCount(): Flow<Int>

    @Query("SELECT DISTINCT thing FROM derived_day")
    suspend fun things(): List<String>
}
