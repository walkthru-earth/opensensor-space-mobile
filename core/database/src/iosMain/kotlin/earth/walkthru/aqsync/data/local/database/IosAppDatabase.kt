@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package earth.walkthru.aqsync.data.local.database

import androidx.room.Room
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
