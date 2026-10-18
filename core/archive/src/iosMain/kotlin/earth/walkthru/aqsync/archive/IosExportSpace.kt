@file:OptIn(ExperimentalForeignApi::class)

package earth.walkthru.aqsync.archive

import earth.walkthru.aqsync.archive.IosFiles.Kind
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSNumber
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSURLVolumeAvailableCapacityForOpportunisticUsageKey
import platform.Foundation.NSUUID
import platform.Foundation.timeIntervalSince1970
import platform.posix.O_CREAT
import platform.posix.O_EXCL
import platform.posix.O_WRONLY
import platform.posix.open
import platform.posix.write

/**
 * [ExportSpace] in the app's temporary directory, `tmp/opensensor-export/run-<uuid>/`. The share
 * sheet and the save picker read a staged copy, never the archive. Runs coexist under the leases of
 * [ExportRuns]. The screen removes its run when the sheet closes, and a run it could not remove ages
 * out after [ExportRuns.MAX_AGE_MS].
 */
class IosExportSpace(private val root: String, private val clock: () -> Long = ::wallClockMs) : ExportSpace {
    override fun newRun(): ExportRun {
        sweepExpired()
        val name = ExportRuns.PREFIX + NSUUID().UUIDString
        // Leased before the directory exists, so no sweep ever sees it unleased while it stages.
        ExportRuns.lease(name)
        val dir = "$root/$name"
        try {
            IosFiles.mkdirs(dir)
        } catch (t: Throwable) {
            ExportRuns.release(name)
            throw t
        }
        return IosExportRun(dir, name, clock)
    }

    /** Removes the released runs past [ExportRuns.MAX_AGE_MS]. Only `run-` directories, never the root. */
    private fun sweepExpired() {
        val now = clock()
        for (child in IosFiles.children(root)) {
            val path = "$root/$child"
            if (IosFiles.kind(path) == Kind.DIRECTORY && ExportRuns.isExpired(child, now) { IosFiles.modifiedEpochMs(path) }) {
                IosFiles.remove(path)
            }
        }
    }

    /** Scratch capacity, the "opportunistic usage" figure that excludes what iOS would purge (#61). */
    override fun freeBytes(): Long? {
        var dir = root
        while (dir.length > 1 && !NSFileManager.defaultManager.fileExistsAtPath(dir)) dir = dir.substringBeforeLast('/').ifEmpty { "/" }
        val key = NSURLVolumeAvailableCapacityForOpportunisticUsageKey
        return (NSURL.fileURLWithPath(dir, true).resourceValuesForKeys(listOf(key), null)?.get(key) as? NSNumber)?.longLongValue
    }

    companion object {
        /** The default space, `NSTemporaryDirectory()/opensensor-export`. */
        fun inTemporaryDirectory(): IosExportSpace = IosExportSpace(temporaryRoot())

        /** Where [inTemporaryDirectory] puts its runs, Scratch for the startup janitor (#60). */
        fun temporaryRoot(): String = NSTemporaryDirectory().trimEnd('/') + "/opensensor-export"
    }
}

private fun wallClockMs(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()

private class IosExportRun(private val dir: String, private val name: String, private val clock: () -> Long) : ExportRun {
    override fun path(name: String): String {
        require(name.isNotEmpty() && '/' !in name && name != "." && name != "..") { "unsafe export name" }
        return "$dir/$name"
    }

    override fun create(name: String): ExportSink {
        val path = path(name)
        val fd = open(path, O_WRONLY or O_CREAT or O_EXCL, 0x180)
        check(fd >= 0) { "cannot create the export file" }
        return PosixSink(fd)
    }

    override fun release() {
        // The age counts from the hand over. The time is set before the lease ends (ExportRuns.isExpired).
        if (ExportRuns.isLeased(name)) IosFiles.setModified(dir, clock())
        ExportRuns.release(name)
    }

    override fun delete() {
        if (IosFiles.kind(dir) != Kind.ABSENT) IosFiles.remove(dir)
        ExportRuns.release(name)
    }
}

private class PosixSink(private val fd: Int) : ExportSink {
    private var closed = false

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size)
        if (length == 0) return
        bytes.usePinned { pinned ->
            var done = 0
            while (done < length) {
                val n = write(fd, pinned.addressOf(offset + done), (length - done).convert())
                check(n > 0) { "cannot write the export file" }
                done += n.toInt()
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        platform.posix.close(fd)
    }
}
