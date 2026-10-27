package space.opensensor.data.local.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import kotlinx.coroutines.flow.Flow
import space.opensensor.data.history.IndexedFileInfo

/**
 * One verified Parquet file in the local archive. Identity is `(thing, name)`; the file is
 * immutable, so `bytes` and `crc32` are consistency checks against the device manifest.
 *
 * A row exists only after the file passed size + CRC-32 + `PAR1` verification and was renamed
 * into place: a partial or mismatched download never gets a row.
 */
@Entity(
    tableName = "archived_file",
    indices = [Index(value = ["thing", "name"], unique = true), Index("templateSha256"), Index("dictionarySha256")],
)
data class ArchivedFile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val thing: String,
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
    val bootId: String? = null,
    /** `file.purpose` of the `opensensor` footer, `telemetry` for thing data, `codec-comparison-duplicate-rows` for benchmark copies. */
    val purpose: String? = null,
    /** Rows with a UTC timestamp and their span (the event time column, held in nanoseconds). 0 / null for unsynced files. */
    val utcRows: Long? = null,
    val firstUtcNs: Long? = null,
    val lastUtcNs: Long? = null,
    val firstMonoUs: Long? = null,
    val lastMonoUs: Long? = null,
    val firstSeq: Long? = null,
    val lastSeq: Long? = null,

    // ---- Estimated time (schema v6). Set only for files with no UTC rows whose boot later received a
    // time anchor (`TimeEstimate`): the span of the file on the estimated UTC axis and the anchor file.
    val estFirstUtcNs: Long? = null,
    val estLastUtcNs: Long? = null,
    val estAnchorFile: String? = null,

    // ---- Footer digests (schema v4). The `dictionary.sha256` and `template.sha256` the file names, so
    // History resolves its template at each derive and a newly served template rebuilds its days.
    val dictionarySha256: String? = null,
    val templateSha256: String? = null,
) {
    /** The same test as [space.opensensor.archive.ArchiveRecord.isIndexed]. */
    val isIndexed: Boolean get() = indexedAtEpochMs != null && indexError == null

    /** The plain record the pure history module works with. */
    fun info() = IndexedFileInfo(name, bootId, purpose, isIndexed, rows, utcRows, firstUtcNs, lastUtcNs, firstMonoUs, lastMonoUs)

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
data class ArchivedFileIdentity(val thing: String, val name: String, val bytes: Long, val crc32: Long)

/** A thing's newest timed end, null when its timed files carry no end. */
data class ThingTimedEnd(val thing: String, val lastNs: Long?)

/** Per-thing archive totals for the device list, computed in SQL instead of over every row. */
data class ThingArchiveGlance(
    val thing: String,
    val files: Int,
    val lastSyncedMs: Long?,
    /** Newest `lastUtcNs` of the thing, null when no file has UTC rows. */
    val lastDataUtcNs: Long?,
)

@Dao
interface ArchivedFileDao {
    @Query("SELECT * FROM archived_file ORDER BY name")
    fun observeAll(): Flow<List<ArchivedFile>>

    @Query("SELECT * FROM archived_file WHERE thing = :thing ORDER BY name")
    fun observeForThing(thing: String): Flow<List<ArchivedFile>>

    @Query("SELECT * FROM archived_file WHERE thing = :thing")
    suspend fun forThing(thing: String): List<ArchivedFile>

    /** Identity columns of one thing's files. Room re-runs it only when `archived_file` changes. */
    @Query("SELECT thing, name, bytes, crc32 FROM archived_file WHERE thing = :thing ORDER BY name")
    fun observeIdentitiesForThing(thing: String): Flow<List<ArchivedFileIdentity>>

    @Query("SELECT thing, name, bytes, crc32 FROM archived_file WHERE thing = :thing ORDER BY name")
    suspend fun identitiesForThing(thing: String): List<ArchivedFileIdentity>

    @Query("SELECT COUNT(*) FROM archived_file WHERE thing = :thing")
    fun observeCountForThing(thing: String): Flow<Int>

    @Query("SELECT EXISTS(SELECT 1 FROM archived_file WHERE thing = :thing)")
    fun observeAnyForThing(thing: String): Flow<Boolean>

    /** One row per thing with files, for the device list (count, last sync, newest data). */
    @Query(
        "SELECT thing, COUNT(*) AS files, MAX(syncedAtEpochMs) AS lastSyncedMs, MAX(lastUtcNs) AS lastDataUtcNs " +
            "FROM archived_file GROUP BY thing"
    )
    fun observeThingGlances(): Flow<List<ThingArchiveGlance>>

    /** True while any file waits for its first index pass, without reading the rows. */
    @Query("SELECT EXISTS(SELECT 1 FROM archived_file WHERE indexedAtEpochMs IS NULL)")
    fun observeAnyNeverIndexed(): Flow<Boolean>

    /** The newest real or estimated end per thing over files History can place, as `ArchiveIndex.latestTimedEnds`. */
    @Query(
        "SELECT thing, MAX(CASE WHEN utcRows > 0 THEN lastUtcNs ELSE estLastUtcNs END) AS lastNs FROM archived_file " +
            "WHERE indexedAtEpochMs IS NOT NULL AND indexError IS NULL AND (purpose IS NULL OR purpose = 'telemetry') " +
            "AND (utcRows > 0 OR (estFirstUtcNs IS NOT NULL AND estLastUtcNs IS NOT NULL)) GROUP BY thing"
    )
    fun observeLatestTimed(): Flow<List<ThingTimedEnd>>

    @Query("SELECT * FROM archived_file WHERE thing = :thing AND name = :name")
    suspend fun get(thing: String, name: String): ArchivedFile?

    @Query("SELECT COUNT(*) FROM archived_file")
    fun observeCount(): Flow<Int>

    @Query("SELECT COALESCE(SUM(bytes), 0) FROM archived_file")
    fun observeTotalBytes(): Flow<Long>

    /**
     * `REPLACE`, not `@Upsert`, for the same reason as `DerivedDayDao.upsert`: auto-generated id plus a
     * unique `(thing, name)` index means `@Upsert` cannot update a row it did not fetch first. The
     * indexer passes fetched rows (id set), but `SyncEngine` writes a fresh row after a verified
     * download and must win over any stale row of the same name.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(file: ArchivedFile)

    /** Inserts only when (thing, name) has no row. Returns the new row id, or -1 when one exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(file: ArchivedFile): Long

    /**
     * Reads the row of `(thing, name)` and writes [transform] of it in one transaction, the same as
     * Room's `withTransaction`, so two writers of different columns never lose each other's change (#60).
     */
    @Transaction
    suspend fun update(thing: String, name: String, transform: (ArchivedFile) -> ArchivedFile) {
        get(thing, name)?.let { upsert(transform(it)) }
    }

    /** Rows not yet indexed (pre-v4 rows, or a previous attempt that failed and should be retried after an app update). */
    @Query("SELECT * FROM archived_file WHERE indexedAtEpochMs IS NULL OR indexError IS NOT NULL ORDER BY (indexedAtEpochMs IS NULL) DESC, name LIMIT :limit")
    suspend fun unindexed(limit: Int): List<ArchivedFile>

    @Query("SELECT COUNT(*) FROM archived_file WHERE indexedAtEpochMs IS NULL OR indexError IS NOT NULL")
    fun observeUnindexedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM archived_file WHERE thing = :thing AND (indexedAtEpochMs IS NULL OR indexError IS NOT NULL)")
    fun observeUnindexedCountForThing(thing: String): Flow<Int>

    /**
     * Indexed telemetry files of one thing on the timeline between [fromNs, toNs): real UTC rows,
     * or an estimated span (§4.6) when the file has none. A file with no start or no end stays out,
     * as in `ArchiveRecord.onTimeline`. Ordered by (real or estimated) first time.
     */
    @Query(
        "SELECT * FROM archived_file WHERE thing = :thing AND indexedAtEpochMs IS NOT NULL AND indexError IS NULL " +
            "AND (purpose IS NULL OR purpose = 'telemetry') " +
            "AND (CASE WHEN utcRows > 0 THEN lastUtcNs ELSE estLastUtcNs END) >= :fromNs " +
            "AND (CASE WHEN utcRows > 0 THEN firstUtcNs ELSE estFirstUtcNs END) < :toNs " +
            "ORDER BY CASE WHEN utcRows > 0 THEN firstUtcNs ELSE estFirstUtcNs END"
    )
    suspend fun withUtcBetween(thing: String, fromNs: Long, toNs: Long): List<ArchivedFile>

    /** Indexed files of one thing (for the time-estimate pass). */
    @Query("SELECT * FROM archived_file WHERE thing = :thing AND indexedAtEpochMs IS NOT NULL AND indexError IS NULL")
    suspend fun indexedForThing(thing: String): List<ArchivedFile>

    /**
     * True when an indexed file's footer names one of [descs], or names one of [dicts] with a desc that
     * is not one of [pinned] (`desc:dict`), on the digest indices. The SQL form of
     * [space.opensensor.archive.ArchiveRecord.mayResolveAnew].
     */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM archived_file WHERE indexedAtEpochMs IS NOT NULL AND indexError IS NULL " +
            "AND (templateSha256 IN (:descs) OR (dictionarySha256 IN (:dicts) " +
            "AND (templateSha256 IS NULL OR templateSha256 || ':' || dictionarySha256 NOT IN (:pinned)))))",
    )
    suspend fun anyIndexedNames(descs: List<String>, dicts: List<String>, pinned: List<String>): Boolean

    @Query("SELECT DISTINCT thing FROM archived_file")
    suspend fun things(): List<String>

    @Query("DELETE FROM archived_file WHERE thing = :thing AND name = :name")
    suspend fun delete(thing: String, name: String)
}

/**
 * Version 3 to 4 adds the footer `dictionary.sha256` and `template.sha256` to `archived_file`. Only
 * the file's footer has them, so every row indexed without error is reopened and the back-fill reads
 * each footer once. Until then a row awaits its index and holds its days in History. Both digests are
 * indexed, so a template change asks in one query whether any file names it.
 */
object Migration3To4 : Migration(3, 4) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE `archived_file` ADD COLUMN `dictionarySha256` TEXT")
        connection.execSQL("ALTER TABLE `archived_file` ADD COLUMN `templateSha256` TEXT")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_archived_file_templateSha256` ON `archived_file` (`templateSha256`)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_archived_file_dictionarySha256` ON `archived_file` (`dictionarySha256`)")
        connection.execSQL("UPDATE `archived_file` SET `indexedAtEpochMs` = NULL WHERE `indexedAtEpochMs` IS NOT NULL AND `indexError` IS NULL")
    }
}
