@file:OptIn(ExperimentalForeignApi::class)

package space.opensensor.storage

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSFileSize
import platform.Foundation.NSFileType
import platform.Foundation.NSFileTypeDirectory
import platform.Foundation.NSFileTypeRegular
import platform.Foundation.NSNumber
import platform.Foundation.timeIntervalSince1970

/** [StorageTree] over `NSFileManager`. `attributesOfItemAtPath` never follows a link. */
class IosStorageTree : StorageTree {
    private val fm: NSFileManager get() = NSFileManager.defaultManager

    override fun list(dir: String): List<StorageEntry> {
        if (fm.attributesOfItemAtPath(dir, null)?.get(NSFileType) != NSFileTypeDirectory) return emptyList()
        @Suppress("UNCHECKED_CAST")
        val relative = (fm.subpathsOfDirectoryAtPath(dir, null) as? List<String>).orEmpty()
        return relative.mapNotNull { path ->
            val attrs = fm.attributesOfItemAtPath("$dir/$path", null) ?: return@mapNotNull null
            val type = attrs[NSFileType]
            StorageEntry(
                path = path,
                isDirectory = type == NSFileTypeDirectory,
                bytes = if (type == NSFileTypeRegular) (attrs[NSFileSize] as? NSNumber)?.longLongValue ?: 0L else 0L,
                modifiedEpochMs = ((attrs[NSFileModificationDate] as? NSDate)?.timeIntervalSince1970 ?: 0.0).times(1000).toLong(),
            )
        }
    }

    override fun delete(path: String) {
        if (fm.attributesOfItemAtPath(path, null) == null) return
        if (!fm.removeItemAtPath(path, null)) throw StorageException("cannot delete")
    }
}
