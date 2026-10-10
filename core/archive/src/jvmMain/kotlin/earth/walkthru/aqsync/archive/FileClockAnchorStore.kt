package earth.walkthru.aqsync.archive

import earth.walkthru.aqsync.storage.AlreadyCommitted
import earth.walkthru.aqsync.storage.AtomicFiles
import earth.walkthru.aqsync.storage.JvmAtomicFiles
import earth.walkthru.aqsync.storage.StorageClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/**
 * Immutable receipts under a dedicated app-private directory, separate from the downloaded archive. A
 * receipt is Durable and written once with [AtomicFiles.createNoReplace], so a second writer can never
 * replace it (#60). Temps live in [AtomicFiles.TEMP_DIR] beside [root].
 */
class FileClockAnchorStore(
    private val root: File,
    private val atomic: AtomicFiles = JvmAtomicFiles(File(root.absoluteFile.parentFile, AtomicFiles.TEMP_DIR)),
) : ClockAnchorStore {
    private val mutex = Mutex()

    override suspend fun anchors(station: String): List<ClockAnchor> = withContext(Dispatchers.IO) {
        require(ArchiveLayout.isSafeStation(station)) { "unsafe station id" }
        mutex.withLock {
            val directory = checked(File(root, station))
            val anchors = directory.listFiles().orEmpty().flatMap { boot ->
                if (!Files.isDirectory(boot.toPath(), NOFOLLOW_LINKS)) return@flatMap emptyList()
                boot.listFiles().orEmpty().mapNotNull { receipt ->
                    if (!Files.isRegularFile(receipt.toPath(), NOFOLLOW_LINKS) || receipt.length() > ClockAnchorReceipt.MAX_BYTES) {
                        return@mapNotNull null
                    }
                    ClockAnchorReceipt.decode(receipt.readBytes(), station, "${boot.name}/${receipt.name}")
                }
            }
            ClockAnchorReceipt.earliest(anchors)
        }
    }

    override suspend fun put(anchor: ClockAnchor) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val receipt = checked(File(root, "${anchor.station}/${ClockAnchorReceipt.relativePath(anchor)}"))
            check(receipt.parentFile.isDirectory || receipt.parentFile.mkdirs()) { "cannot create clock receipt directory" }
            val bytes = ClockAnchorReceipt.encode(anchor)
            try {
                atomic.createNoReplace(receipt.path, bytes, StorageClass.Durable)
            } catch (_: AlreadyCommitted) {
                check(receipt.isFile && receipt.readBytes().contentEquals(bytes)) { "conflicting clock receipt" }
            }
        }
    }

    private fun checked(file: File): File {
        var current: File? = file.absoluteFile
        val boundary = root.absoluteFile
        while (current != null) {
            check(!Files.isSymbolicLink(current.toPath())) { "refusing a symbolic link in clock receipts" }
            if (current == boundary) break
            current = current.parentFile
        }
        return file
    }
}
