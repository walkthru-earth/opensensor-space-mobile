package earth.walkthru.aqsync.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
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
 */
@Database(entities = [KnownDevice::class, ArchivedFile::class], version = 4, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun knownDeviceDao(): KnownDeviceDao
    abstract fun archivedFileDao(): ArchivedFileDao

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
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
    }
}
