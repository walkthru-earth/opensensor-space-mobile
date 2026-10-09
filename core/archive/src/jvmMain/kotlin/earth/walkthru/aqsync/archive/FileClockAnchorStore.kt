package earth.walkthru.aqsync.archive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE

/** Immutable receipts under a dedicated app-private directory, separate from the downloaded archive. */
class FileClockAnchorStore(private val root: File) : ClockAnchorStore {
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
            if (receipt.exists()) {
                check(receipt.isFile && receipt.readBytes().contentEquals(ClockAnchorReceipt.encode(anchor))) { "conflicting clock receipt" }
                return@withLock
            }
            val temporary = File.createTempFile(".clock-", ".tmp", receipt.parentFile)
            try {
                FileOutputStream(temporary).use { stream ->
                    stream.write(ClockAnchorReceipt.encode(anchor))
                    stream.fd.sync()
                }
                Files.move(temporary.toPath(), receipt.toPath(), ATOMIC_MOVE)
            } finally {
                temporary.delete()
            }
            Unit
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
