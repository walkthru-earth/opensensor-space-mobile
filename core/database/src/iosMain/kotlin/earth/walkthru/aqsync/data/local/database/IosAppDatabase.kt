@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package earth.walkthru.aqsync.data.local.database

import androidx.room.Room
import androidx.room.useWriterConnection
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionCompleteUntilFirstUserAuthentication
import platform.Foundation.NSFileProtectionKey
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey

/**
 * The database file at [path]. Its directory is created first, as SQLite does not create one, with the
 * archive's protection class (readable after the first unlock, so a sync that runs while the phone is
 * locked can still write) and excluded from backups like the files the index describes.
 */
fun openAppDatabase(path: String): AppDatabase {
    val dir = path.substringBeforeLast('/', missingDelimiterValue = "")
    if (dir.isNotEmpty()) {
        val fm = NSFileManager.defaultManager
        val protection = mapOf<Any?, Any?>(NSFileProtectionKey to NSFileProtectionCompleteUntilFirstUserAuthentication)
        check(fm.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = protection, error = null)) {
            "database directory unavailable"
        }
        NSURL.fileURLWithPath(dir, isDirectory = true).setResourceValue(true, NSURLIsExcludedFromBackupKey, null)
    }
    return AppDatabase.configured(Room.databaseBuilder<AppDatabase>(name = path))
}

/**
 * Makes every commit so far survive a power loss. Room runs WAL with `synchronous = NORMAL`, where the
 * last commits can roll back after a power cut. A full checkpoint copies the WAL into the database file
 * and syncs it, with `F_FULLFSYNC` like the archive's Durable files. Throws when a reader kept it from
 * finishing, so the caller retries.
 */
suspend fun AppDatabase.checkpointDurably() {
    useWriterConnection { connection ->
        connection.usePrepared("PRAGMA checkpoint_fullfsync = 1") { it.step() }
        connection.usePrepared("PRAGMA wal_checkpoint(FULL)") { statement ->
            check(statement.step() && statement.getLong(0) == 0L) { "database checkpoint did not finish" }
        }
    }
}
