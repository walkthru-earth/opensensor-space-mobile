package space.opensensor.data.local.database

import androidx.room.Room
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

actual fun inMemoryAppDatabase(): AppDatabase = AppDatabase.configured(Room.inMemoryDatabaseBuilder<AppDatabase>())

actual fun fileAppDatabase(path: String): AppDatabase = openAppDatabase(path)

actual fun newTempDir(): String = NSTemporaryDirectory().trimEnd('/') + "/app-database-test-" + NSUUID().UUIDString()

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
actual fun deleteTempDir(dir: String) {
    NSFileManager.defaultManager.removeItemAtPath(dir, null)
}
