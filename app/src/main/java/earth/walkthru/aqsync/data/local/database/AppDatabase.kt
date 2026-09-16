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
 */
@Database(entities = [KnownDevice::class, ArchivedFile::class], version = 3, exportSchema = true)
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
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
    }
}
