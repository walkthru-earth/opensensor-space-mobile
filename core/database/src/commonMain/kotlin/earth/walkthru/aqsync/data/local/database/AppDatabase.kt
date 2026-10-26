package earth.walkthru.aqsync.data.local.database

import androidx.room.AutoMigration
import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.DeleteTable
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

/**
 * The one database of both phones, `aq-sync.db`, opened through [configured] with the bundled SQLite
 * driver. Android uses every table. iOS uses `archived_file` and `pruned_file`, the other tables stay
 * empty there, so one schema and one version serve both (#100).
 *
 * Schema history (JSON under `core/database/schemas/`):
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
 * - v10: `sensor_installation`, the phone's record of each sensor fitted to a station.
 * - v11: no schema change. Rows indexed with no UTC rows and no error are marked never indexed, so
 *   the `event_time_utc_us` reader indexes them again (`ArchiveRecord.INDEX_LAYOUT` 2).
 * - v12: `pruned_file`, the files the user removed from this phone in Settings, which sync skips while
 *   the device lists them at the same size. A whole LIST that no longer names one drops it, and Download
 *   again clears them all; `AutoMigration` (new table only).
 * - v13: History reads each file by its board template (#186). `archived_file.pmValidRows` is dropped,
 *   `metric_bucket` gains `sampleIntervalMs` and its metric ids become column names, so the tier cache
 *   (`metric_bucket` and `derived_day`) is dropped and rebuilt from the archive. Devices and the archive
 *   index stay.
 */
@ConstructedBy(AppDatabaseConstructor::class)
@Database(
    entities = [KnownDevice::class, ArchivedFile::class, MetricBucket::class, DerivedDay::class, StationEpoch::class, SensorInstallation::class, PrunedFileEntity::class],
    version = 13,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6), AutoMigration(from = 6, to = 7, spec = AppDatabase.DropWideTiers::class), AutoMigration(from = 7, to = 8), AutoMigration(from = 11, to = 12)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun knownDeviceDao(): KnownDeviceDao
    abstract fun archivedFileDao(): ArchivedFileDao
    abstract fun metricBucketDao(): MetricBucketDao
    abstract fun derivedDayDao(): DerivedDayDao
    abstract fun stationEpochDao(): StationEpochDao
    abstract fun sensorInstallationDao(): SensorInstallationDao
    abstract fun prunedFileDao(): PrunedFileDao

    @DeleteTable(tableName = "aggregate_bucket")
    class DropWideTiers : AutoMigrationSpec

    companion object {
        /** The file name on both phones. */
        const val FILE_NAME: String = "aq-sync.db"

        val MIGRATION_1_2: Migration = sqlMigration(1, 2, "ALTER TABLE known_device ADD COLUMN lanToken TEXT", "ALTER TABLE known_device ADD COLUMN lanPort INTEGER")
        val MIGRATION_2_3: Migration = sqlMigration(2, 3, "ALTER TABLE known_device ADD COLUMN autoSync INTEGER NOT NULL DEFAULT 0")
        val MIGRATION_3_4: Migration = sqlMigration(
            3,
            4,
            *listOf(
                "indexedAtEpochMs INTEGER", "indexError TEXT", "rows INTEGER", "rowGroups INTEGER", "schemaVersion TEXT",
                "bootId TEXT", "purpose TEXT", "utcRows INTEGER", "firstUtcNs INTEGER", "lastUtcNs INTEGER",
                "firstMonoUs INTEGER", "lastMonoUs INTEGER", "firstSeq INTEGER", "lastSeq INTEGER", "pmValidRows INTEGER",
            ).map { "ALTER TABLE archived_file ADD COLUMN $it" }.toTypedArray(),
        )
        val MIGRATION_8_9: Migration = sqlMigration(
            8,
            9,
            "CREATE TABLE IF NOT EXISTS station_epoch (address TEXT NOT NULL, station TEXT NOT NULL, deviceId TEXT, firstSeenEpochMs INTEGER NOT NULL, observedFromAuthenticatedBle INTEGER NOT NULL, PRIMARY KEY(address, station))",
            "INSERT INTO station_epoch (address, station, deviceId, firstSeenEpochMs, observedFromAuthenticatedBle) SELECT address, station, deviceId, COALESCE(lastConnectedEpochMs, lastSeenEpochMs), 0 FROM known_device WHERE station IS NOT NULL",
            // Older archives may already belong to previous station generations. Their recorded
            // source address preserves provenance, but cannot establish the historical hardware ID.
            "INSERT OR IGNORE INTO station_epoch (address, station, deviceId, firstSeenEpochMs, observedFromAuthenticatedBle) SELECT sourceDevice, station, NULL, MIN(syncedAtEpochMs), 0 FROM archived_file GROUP BY sourceDevice, station",
        )
        val MIGRATION_9_10: Migration = sqlMigration(
            9,
            10,
            "CREATE TABLE IF NOT EXISTS sensor_installation (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, address TEXT NOT NULL, station TEXT NOT NULL, deviceId TEXT, vendor TEXT NOT NULL, model TEXT NOT NULL, serial TEXT, batch TEXT, startedAtEpochMs INTEGER NOT NULL, endedAtEpochMs INTEGER, source TEXT NOT NULL)",
            "CREATE INDEX IF NOT EXISTS index_sensor_installation_address ON sensor_installation (address)",
            "CREATE INDEX IF NOT EXISTS index_sensor_installation_station ON sensor_installation (station)",
        )

        /** The v11 statement, also run by tests against rows written as v10 left them. */
        const val MIGRATION_10_11_SQL: String =
            "UPDATE archived_file SET indexedAtEpochMs = NULL WHERE indexedAtEpochMs IS NOT NULL AND indexError IS NULL AND COALESCE(utcRows, 0) = 0"
        val MIGRATION_10_11: Migration = sqlMigration(10, 11, MIGRATION_10_11_SQL)
        val MIGRATION_12_13: Migration = sqlMigration(
            12,
            13,
            "ALTER TABLE archived_file DROP COLUMN pmValidRows",
            // The tiers are a cache of the archive. Old rows carry the old metric ids, so drop them
            // and let the deriver rebuild every day.
            "DROP TABLE IF EXISTS metric_bucket",
            "CREATE TABLE IF NOT EXISTS metric_bucket (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, station TEXT NOT NULL, metricId TEXT NOT NULL, resolution INTEGER NOT NULL, startEpochS INTEGER NOT NULL, rows INTEGER NOT NULL, validRows INTEGER NOT NULL, estimatedRows INTEGER NOT NULL, mean REAL NOT NULL, min REAL NOT NULL, max REAL NOT NULL, sampleIntervalMs INTEGER NOT NULL)",
            "CREATE UNIQUE INDEX IF NOT EXISTS index_metric_bucket_station_metricId_resolution_startEpochS ON metric_bucket (station, metricId, resolution, startEpochS)",
            "DELETE FROM derived_day",
        )
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_12_13)

        /**
         * The settings both phones open the database with: every migration, the bundled SQLite driver
         * (the same SQLite build and file format on Android and iOS) and queries on the IO dispatcher.
         */
        fun configured(builder: RoomDatabase.Builder<AppDatabase>): AppDatabase = builder
            .addMigrations(*MIGRATIONS)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()

        private fun sqlMigration(from: Int, to: Int, vararg statements: String): Migration = object : Migration(from, to) {
            override fun migrate(connection: SQLiteConnection) {
                for (sql in statements) connection.execSQL(sql)
            }
        }
    }
}

/** Room generates the actual for each target, so iOS opens the database without reflection. */
@Suppress("KotlinNoActualForExpect")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}
