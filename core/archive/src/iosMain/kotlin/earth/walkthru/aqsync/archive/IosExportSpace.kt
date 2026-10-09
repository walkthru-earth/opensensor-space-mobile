@file:OptIn(ExperimentalForeignApi::class)

package earth.walkthru.aqsync.archive

import earth.walkthru.aqsync.archive.IosFiles.Kind
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.posix.O_CREAT
import platform.posix.O_EXCL
import platform.posix.O_WRONLY
import platform.posix.open
import platform.posix.write

/**
 * [ExportSpace] in the app's temporary directory, `tmp/opensensor-export/run-<uuid>/`. The share
 * sheet and the save picker read a staged copy, never the archive. A new run removes the older ones,
 * and the screen removes its run when the sheet closes.
 */
class IosExportSpace(private val root: String) : ExportSpace {
    override fun newRun(): ExportRun {
        for (child in IosFiles.walk(root).filter { '/' !in it }) IosFiles.remove("$root/$child")
        val dir = "$root/run-${NSUUID().UUIDString}"
        IosFiles.mkdirs(dir)
        return IosExportRun(dir)
    }

    companion object {
        /** The default space, `NSTemporaryDirectory()/opensensor-export`. */
        fun inTemporaryDirectory(): IosExportSpace = IosExportSpace(NSTemporaryDirectory().trimEnd('/') + "/opensensor-export")
    }
}

private class IosExportRun(private val dir: String) : ExportRun {
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

    override fun delete() {
        if (IosFiles.kind(dir) != Kind.ABSENT) IosFiles.remove(dir)
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
