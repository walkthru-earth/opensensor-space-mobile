package earth.walkthru.aqsync.data.local.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import earth.walkthru.aqsync.data.history.IndexedFileInfo
import kotlinx.coroutines.flow.Flow

/**
 * One verified Parquet file in the local archive. Identity is `(station, name)`; the file is
 * immutable, so `bytes` and `crc32` are consistency checks against the device manifest.
 *
 * A row exists only after the file passed size + CRC-32 + `PAR1` verification and was renamed
 * into place: a partial or mismatched download never gets a row.
 */
@Entity(
    tableName = "archived_file",
    indices = [Index(value = ["station", "name"], unique = true)],
)
data class ArchivedFile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val station: String,
    /** Relative Hive path exactly as the device reported it. */
    val name: String,
    val bytes: Long,
    /** CRC-32 (IEEE) as an unsigned 32-bit value. */
    val crc32: Long,
    val sha256: String,
    val syncedAtEpochMs: Long,
    /** Bluetooth address (or fake id) of the device the copy came from. */
    val sourceDevice: String,

    // ---- History index (schema v4). All nullable: null means "not indexed yet" (a row written before
    // v4, or a file the reader refused — see indexError). Derived from the file's own footer/rows by
    // `ParquetIndexer`, never from the Hive name.
    /** When the index below was computed; null until then. */
    val indexedAtEpochMs: Long? = null,
    /** Why indexing failed (`ParquetFormatException` message), else null. */
    val indexError: String? = null,
    val rows: Long? = null,
    val rowGroups: Int? = null,
    /** `schema_version` file metadata (`cores3-telemetry-v1|v2|v3`). */
    val schemaVersion: String? = null,
    val bootId: String? = null,
    /** `purpose` file metadata; `telemetry` for station data, `codec-comparison-duplicate-rows` for benchmark copies. */
    val purpose: String? = null,
    /** Rows with a UTC timestamp and their span (`event_time_utc_ns`). 0 / null for unsynced files. */
    val utcRows: Long? = null,
    val firstUtcNs: Long? = null,
    val lastUtcNs: Long? = null,
    val firstMonoUs: Long? = null,
    val lastMonoUs: Long? = null,
    val firstSeq: Long? = null,
    val lastSeq: Long? = null,
    /** Rows with `pms_status == 4`. */
    val pmValidRows: Long? = null,

    // ---- Estimated time (schema v6). Set only for files with no UTC rows whose boot later received a
    // time anchor (`TimeEstimate`): the span of the file on the estimated UTC axis and the anchor file.
    val estFirstUtcNs: Long? = null,
    val estLastUtcNs: Long? = null,
    val estAnchorFile: String? = null,
) {
    val indexed: Boolean get() = indexedAtEpochMs != null && indexError == null

    /** The plain record the pure history module works with. */
    fun info() = IndexedFileInfo(name, bootId, purpose, indexed, rows, utcRows, firstUtcNs, lastUtcNs, firstMonoUs, lastMonoUs)

    /** The file has real UTC rows. */
    val hasUtc: Boolean get() = (utcRows ?: 0L) > 0L

    /** The file is placed on the timeline by an estimate only. */
    val hasEstimatedUtc: Boolean get() = !hasUtc && estFirstUtcNs != null && estLastUtcNs != null

    /** Real or estimated span start/end, whichever the file has. */
    val timelineFirstNs: Long? get() = if (hasUtc) firstUtcNs else estFirstUtcNs
    val timelineLastNs: Long? get() = if (hasUtc) lastUtcNs else estLastUtcNs
}

/**
 * The identity columns of one archived file, all the sync planner compares against a listing. A
 * projection, so a planner input never loads the history index columns (#63).
 */
data class ArchivedFileIdentity(val station: String, val name: String, val bytes: Long, val crc32: Long)

/** A station's newest timed end, null when its timed files carry no end. */
data class StationTimedEnd(val station: String, val lastNs: Long?)

/** Per-station archive totals for the device list, computed in SQL instead of over every row. */
data class StationArchiveGlance(
    val station: String,
    val files: Int,
    val lastSyncedMs: Long?,
    /** Newest `lastUtcNs` of the station, null when no file has UTC rows. */
    val lastDataUtcNs: Long?,
)

@Dao
interface ArchivedFileDao {
    @Query("SELECT * FROM archived_file ORDER BY name")
    fun observeAll(): Flow<List<ArchivedFile>>

    @Query("SELECT * FROM archived_file WHERE station = :station ORDER BY name")
    fun observeForStation(station: String): Flow<List<ArchivedFile>>

    @Query("SELECT * FROM archived_file WHERE station = :station")
    suspend fun forStation(station: String): List<ArchivedFile>

    /** Identity columns of one station's files. Room re-runs it only when `archived_file` changes. */
    @Query("SELECT station, name, bytes, crc32 FROM archived_file WHERE station = :station ORDER BY name")
    fun observeIdentitiesForStation(station: String): Flow<List<ArchivedFileIdentity>>

    @Query("SELECT station, name, bytes, crc32 FROM archived_file WHERE station = :station ORDER BY name")
    suspend fun identitiesForStation(station: String): List<ArchivedFileIdentity>

    @Query("SELECT COUNT(*) FROM archived_file WHERE station = :station")
    fun observeCountForStation(station: String): Flow<Int>

    @Query("SELECT EXISTS(SELECT 1 FROM archived_file WHERE station = :station)")
    fun observeAnyForStation(station: String): Flow<Boolean>

    /** One row per station with files, for the device list (count, last sync, newest data). */
    @Query(
        "SELECT station, COUNT(*) AS files, MAX(syncedAtEpochMs) AS lastSyncedMs, MAX(lastUtcNs) AS lastDataUtcNs " +
            "FROM archived_file GROUP BY station"
    )
    fun observeStationGlances(): Flow<List<StationArchiveGlance>>

    /** True while any file waits for its first index pass, without reading the rows. */
    @Query("SELECT EXISTS(SELECT 1 FROM archived_file WHERE indexedAtEpochMs IS NULL)")
    fun observeAnyNeverIndexed(): Flow<Boolean>

    /** The newest real or estimated end per station over files History can place, as `ArchiveIndex.latestTimedEnds`. */
    @Query(
        "SELECT station, MAX(CASE WHEN utcRows > 0 THEN lastUtcNs ELSE estLastUtcNs END) AS lastNs FROM archived_file " +
            "WHERE indexedAtEpochMs IS NOT NULL AND indexError IS NULL AND (purpose IS NULL OR purpose = 'telemetry') " +
            "AND (utcRows > 0 OR (estFirstUtcNs IS NOT NULL AND estLastUtcNs IS NOT NULL)) GROUP BY station"
    )
    fun observeLatestTimed(): Flow<List<StationTimedEnd>>

    @Query("SELECT * FROM archived_file WHERE station = :station AND name = :name")
    suspend fun get(station: String, name: String): ArchivedFile?

    @Query("SELECT COUNT(*) FROM archived_file")
    fun observeCount(): Flow<Int>

    @Query("SELECT COALESCE(SUM(bytes), 0) FROM archived_file")
    fun observeTotalBytes(): Flow<Long>

    /**
     * `REPLACE`, not `@Upsert`, for the same reason as `DerivedDayDao.upsert`: auto-generated id plus a
     * unique `(station, name)` index means `@Upsert` cannot update a row it did not fetch first. The
     * indexer passes fetched rows (id set), but `SyncEngine` writes a fresh row after a verified
     * download and must win over any stale row of the same name.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(file: ArchivedFile)

    /** Inserts only when (station, name) has no row. Returns the new row id, or -1 when one exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(file: ArchivedFile): Long

    /**
     * Reads the row of `(station, name)` and writes [transform] of it in one transaction, the same as
     * Room's `withTransaction`, so two writers of different columns never lose each other's change (#60).
     */
    @Transaction
    suspend fun update(station: String, name: String, transform: (ArchivedFile) -> ArchivedFile) {
        get(station, name)?.let { upsert(transform(it)) }
    }

    /** Rows not yet indexed (pre-v4 rows, or a previous attempt that failed and should be retried after an app update). */
    @Query("SELECT * FROM archived_file WHERE indexedAtEpochMs IS NULL OR indexError IS NOT NULL ORDER BY (indexedAtEpochMs IS NULL) DESC, name LIMIT :limit")
    suspend fun unindexed(limit: Int): List<ArchivedFile>

    @Query("SELECT COUNT(*) FROM archived_file WHERE indexedAtEpochMs IS NULL OR indexError IS NOT NULL")
    fun observeUnindexedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM archived_file WHERE station = :station AND (indexedAtEpochMs IS NULL OR indexError IS NOT NULL)")
    fun observeUnindexedCountForStation(station: String): Flow<Int>

    /**
     * Telemetry files of one station on the timeline between [fromNs, toNs): real UTC rows, or an
     * estimated span (§4.6) when the file has none. Ordered by (real or estimated) first time.
     */
    @Query(
        "SELECT * FROM archived_file WHERE station = :station AND indexError IS NULL " +
            "AND (purpose IS NULL OR purpose = 'telemetry') " +
            "AND COALESCE(CASE WHEN utcRows > 0 THEN lastUtcNs ELSE estLastUtcNs END, -9223372036854775808) >= :fromNs " +
            "AND COALESCE(CASE WHEN utcRows > 0 THEN firstUtcNs ELSE estFirstUtcNs END, 9223372036854775807) < :toNs " +
            "ORDER BY CASE WHEN utcRows > 0 THEN firstUtcNs ELSE estFirstUtcNs END"
    )
    suspend fun withUtcBetween(station: String, fromNs: Long, toNs: Long): List<ArchivedFile>

    /** Indexed files of one station (for the time-estimate pass). */
    @Query("SELECT * FROM archived_file WHERE station = :station AND indexedAtEpochMs IS NOT NULL AND indexError IS NULL")
    suspend fun indexedForStation(station: String): List<ArchivedFile>

    @Query("SELECT DISTINCT station FROM archived_file")
    suspend fun stations(): List<String>

    @Query("DELETE FROM archived_file WHERE station = :station AND name = :name")
    suspend fun delete(station: String, name: String)
}
