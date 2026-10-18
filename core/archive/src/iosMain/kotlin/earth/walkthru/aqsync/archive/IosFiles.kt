@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package earth.walkthru.aqsync.archive

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.Foundation.NSData
import platform.Foundation.NSDataWritingAtomic
import platform.Foundation.NSDataWritingFileProtectionCompleteUntilFirstUserAuthentication
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSFileProtectionCompleteUntilFirstUserAuthentication
import platform.Foundation.NSFileProtectionKey
import platform.Foundation.NSFileSize
import platform.Foundation.NSFileType
import platform.Foundation.NSFileTypeDirectory
import platform.Foundation.NSFileTypeRegular
import platform.Foundation.NSFileTypeSymbolicLink
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile
import platform.posix.ENOENT
import platform.posix.errno
import platform.posix.lstat
import platform.posix.memcpy
import platform.posix.stat

/**
 * Foundation file helpers shared by [IosArchiveStore] and [LegacyReceiptImport]. Every type check
 * uses `attributesOfItemAtPath`, which does not follow a symlink, so a link is seen as a link.
 */
internal object IosFiles {
    private val fm: NSFileManager get() = NSFileManager.defaultManager

    /**
     * Readable after the first unlock, like the Keychain's AfterFirstUnlockThisDeviceOnly token, so a
     * collection that continues while the phone locks can still write. New files inherit it.
     */
    val protection: Map<Any?, Any?> = mapOf(NSFileProtectionKey to NSFileProtectionCompleteUntilFirstUserAuthentication)

    enum class Kind { ABSENT, FILE, DIRECTORY, LINK, OTHER }

    fun kind(path: String): Kind {
        val attrs = fm.attributesOfItemAtPath(path, null) ?: return Kind.ABSENT
        return when (attrs[NSFileType]) {
            NSFileTypeRegular -> Kind.FILE
            NSFileTypeDirectory -> Kind.DIRECTORY
            NSFileTypeSymbolicLink -> Kind.LINK
            else -> Kind.OTHER
        }
    }

    /**
     * True only when `lstat` says nothing is at [path] (ENOENT). A path that cannot be checked for any
     * other reason, such as a denied permission, is not gone.
     */
    fun isGone(path: String): Boolean = memScoped { lstat(path, alloc<stat>().ptr) != 0 && errno == ENOENT }

    fun size(path: String): Long = (fm.attributesOfItemAtPath(path, null)?.get(NSFileSize) as? NSNumber)?.longLongValue ?: 0L

    /** The modification time of [path] in epoch milliseconds, or null when it is absent. */
    fun modifiedEpochMs(path: String): Long? =
        (fm.attributesOfItemAtPath(path, null)?.get(NSFileModificationDate) as? NSDate)?.let { (it.timeIntervalSince1970 * 1000.0).toLong() }

    /** Sets the modification time of [path]. Does nothing when it is absent. */
    fun setModified(path: String, epochMs: Long) {
        if (kind(path) == Kind.ABSENT) return
        fm.setAttributes(mapOf<Any?, Any?>(NSFileModificationDate to NSDate.dateWithTimeIntervalSince1970(epochMs / 1000.0)), path, null)
    }

    /** The names directly in [dir], without following links. Empty when [dir] is absent or cannot be listed. */
    fun children(dir: String): List<String> {
        if (kind(dir) != Kind.DIRECTORY) return emptyList()
        @Suppress("UNCHECKED_CAST")
        return (fm.contentsOfDirectoryAtPath(dir, null) as? List<String>).orEmpty()
    }

    /** Refuses a symlink at [path] or at any existing ancestor below [root], as the old iOS archive did. */
    fun checkNoLinks(path: String, root: String) {
        var current = path
        while (current.length > root.length && current.startsWith("$root/")) {
            if (kind(current) == Kind.LINK) throw IllegalStateException("refusing a symbolic link in the archive")
            current = current.substringBeforeLast('/')
        }
    }

    fun mkdirs(dir: String) {
        if (kind(dir) == Kind.DIRECTORY) return
        check(fm.createDirectoryAtPath(dir, true, protection, null)) { "cannot create archive directory" }
    }

    /** Creates [root] with the protection class and excludes it, and so everything below, from backups. */
    fun prepareRoot(root: String) {
        mkdirs(root)
        NSURL.fileURLWithPath(root, true).setResourceValue(true, NSURLIsExcludedFromBackupKey, null)
    }

    fun createEmpty(path: String) {
        check(fm.createFileAtPath(path, null, protection)) { "cannot create archive file" }
    }

    fun remove(path: String) {
        if (kind(path) != Kind.ABSENT) fm.removeItemAtPath(path, null)
    }

    /** A rename that fails when [to] exists, so a final copy is never replaced. */
    fun move(from: String, to: String) {
        memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            check(fm.moveItemAtPath(from, to, error.ptr)) { "cannot move into the archive: ${error.value?.localizedDescription}" }
        }
    }

    /** Paths below [dir], relative to it, without following links. Empty when [dir] is absent or cannot be listed. */
    fun walk(dir: String): List<String> = walkOrNull(dir).orEmpty()

    /** As [walk], but null when [dir] is a directory that cannot be listed, so a caller can tell it from an empty one. */
    fun walkOrNull(dir: String): List<String>? {
        if (kind(dir) != Kind.DIRECTORY) return emptyList()
        @Suppress("UNCHECKED_CAST")
        return fm.subpathsOfDirectoryAtPath(dir, null) as? List<String>
    }

    /** A small regular file's bytes, or null when absent, not a regular file or over [maxBytes]. */
    fun readSmall(path: String, maxBytes: Long): ByteArray? {
        if (kind(path) != Kind.FILE || size(path) > maxBytes) return null
        return NSData.dataWithContentsOfFile(path)?.toByteArray()
    }

    /** Replaces [path] atomically with [bytes], under the archive protection class. */
    fun writeAtomically(path: String, bytes: ByteArray) {
        val options = NSDataWritingAtomic or NSDataWritingFileProtectionCompleteUntilFirstUserAuthentication
        check(bytes.toNSData().writeToFile(path, options, null)) { "cannot write archive metadata" }
    }

    private fun ByteArray.toNSData(): NSData =
        if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

    private fun NSData.toByteArray(): ByteArray {
        val count = length.toInt()
        val out = ByteArray(count)
        if (count > 0) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
        return out
    }
}
