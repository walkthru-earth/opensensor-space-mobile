package earth.walkthru.aqsync.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [KnownDevice::class, ArchivedFile::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun knownDeviceDao(): KnownDeviceDao
    abstract fun archivedFileDao(): ArchivedFileDao
}
