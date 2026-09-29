package earth.walkthru.aqsync.data.local.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.DeleteTable
import androidx.room.RoomDatabase
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema history (JSON under `app/schemas/`):
 * - v1: `known_device`, `archived_file`.
 * - v2: `known_device.lanToken` (hex, nullable) and `known_device.lanPort` for the LAN transport.
 * - v3: `known_device.autoSync` (0/1, default 0) — per-device opt-in to background syncs.
 * - v4: `archived_file` history index columns (rows, row groups, schema version, UTC/monotonic/sequence
 *   spans, PMS-valid rows, indexedAt/indexError), all nullable; existing rows are back-filled lazily by
 *   `HistoryIndexer` from the files already in the archive.
 * - v5: `aggregate_bucket` (15 min / 1 h / 1 day tiers) and `derived_day` (per station-day source
 *   signature), both pure caches of the archive rebuilt by `HistoryDeriver`; added with an
 *   `AutoMigration` (new tables only).
 * - v6: `archived_file.estFirstUtcNs/estLastUtcNs/estAnchorFile` (estimated time for unsynced files whose
 *   boot later got a clock) and `aggregate_bucket.estimatedRows` (default 0); `AutoMigration`.
 * - v7: `metric_bucket` (one row per metric — the registry decides which) replaces the wide
 *   `aggregate_bucket`, which is dropped; `AutoMigration` with `DeleteTable`. Tiers are a cache: the
 *   deriver's signature version bump rebuilds every day on first run.
 * - v8: `known_device.companionAssociationId` (nullable) — the Companion Device Manager association
 *   behind "wake when nearby"; `AutoMigration` (new nullable column).
 * - v9: `station_epoch` preserves the station generations associated with an enrolled endpoint;
 *   explicit migration seeds existing known stations without claiming recorded BLE verification.
 */
@Database(
    entities = [KnownDevice::class, ArchivedFile::class, MetricBucket::class, DerivedDay::class, StationEpoch::class],
    version = 9,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6), AutoMigration(from = 6, to = 7, spec = AppDatabase.DropWideTiers::class), AutoMigration(from = 7, to = 8)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun knownDeviceDao(): KnownDeviceDao
    abstract fun archivedFileDao(): ArchivedFileDao
    abstract fun metricBucketDao(): MetricBucketDao
    abstract fun derivedDayDao(): DerivedDayDao
    abstract fun stationEpochDao(): StationEpochDao

    @DeleteTable(tableName = "aggregate_bucket")
    class DropWideTiers : AutoMigrationSpec

    companion object {
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE known_device ADD COLUMN lanToken TEXT")
                db.execSQL("ALTER TABLE known_device ADD COLUMN lanPort INTEGER")
            }
        }
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE known_device ADD COLUMN autoSync INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (column in listOf(
                    "indexedAtEpochMs INTEGER", "indexError TEXT", "rows INTEGER", "rowGroups INTEGER", "schemaVersion TEXT",
                    "bootId TEXT", "purpose TEXT", "utcRows INTEGER", "firstUtcNs INTEGER", "lastUtcNs INTEGER",
                    "firstMonoUs INTEGER", "lastMonoUs INTEGER", "firstSeq INTEGER", "lastSeq INTEGER", "pmValidRows INTEGER",
                )) {
                    db.execSQL("ALTER TABLE archived_file ADD COLUMN $column")
                }
            }
        }
        val MIGRATION_8_9: Migration = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS station_epoch (address TEXT NOT NULL, station TEXT NOT NULL, deviceId TEXT, firstSeenEpochMs INTEGER NOT NULL, observedFromAuthenticatedBle INTEGER NOT NULL, PRIMARY KEY(address, station))")
                db.execSQL("INSERT INTO station_epoch (address, station, deviceId, firstSeenEpochMs, observedFromAuthenticatedBle) SELECT address, station, deviceId, COALESCE(lastConnectedEpochMs, lastSeenEpochMs), 0 FROM known_device WHERE station IS NOT NULL")
                // Older archives may already belong to previous station generations. Their recorded
                // source address preserves provenance, but cannot establish the historical hardware ID.
                db.execSQL("INSERT OR IGNORE INTO station_epoch (address, station, deviceId, firstSeenEpochMs, observedFromAuthenticatedBle) SELECT sourceDevice, station, NULL, MIN(syncedAtEpochMs), 0 FROM archived_file GROUP BY sourceDevice, station")
            }
        }
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_8_9)
    }
}
