package earth.walkthru.aqsync.data.local.database

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import java.io.File
import java.nio.file.Files

/** Host tests have no real Context. Room asks it only for the database path and the application. */
private class PathContext : ContextWrapper(null) {
    override fun getApplicationContext(): Context = this
    override fun getDatabasePath(name: String): File = File(name)
}

actual fun inMemoryAppDatabase(): AppDatabase = AppDatabase.configured(Room.inMemoryDatabaseBuilder<AppDatabase>(PathContext()))

actual fun fileAppDatabase(path: String): AppDatabase {
    File(path).parentFile?.mkdirs()
    return AppDatabase.configured(Room.databaseBuilder<AppDatabase>(PathContext(), path))
}

actual fun newTempDir(): String = Files.createTempDirectory("app-database-test").toFile().absolutePath

actual fun deleteTempDir(dir: String) {
    File(dir).deleteRecursively()
}
