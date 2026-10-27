package space.opensensor.data.local.database

import androidx.room.Room
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

actual fun inMemoryAppDatabase(): AppDatabase = AppDatabase.configured(Room.inMemoryDatabaseBuilder<AppDatabase>())

actual fun fileAppDatabase(path: String): AppDatabase = openAppDatabase(path)

/** Made on disk, as on Android, so a test can write its own database file in it first. */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
actual fun newTempDir(): String = (NSTemporaryDirectory().trimEnd('/') + "/app-database-test-" + NSUUID().UUIDString()).also {
    NSFileManager.defaultManager.createDirectoryAtPath(it, withIntermediateDirectories = true, attributes = null, error = null)
}

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
actual fun deleteTempDir(dir: String) {
    NSFileManager.defaultManager.removeItemAtPath(dir, null)
}
