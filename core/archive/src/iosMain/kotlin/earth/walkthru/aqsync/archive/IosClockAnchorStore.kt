package earth.walkthru.aqsync.archive

import earth.walkthru.aqsync.storage.AlreadyCommitted
import earth.walkthru.aqsync.storage.AtomicFiles
import earth.walkthru.aqsync.storage.IosAtomicFiles
import earth.walkthru.aqsync.storage.StorageClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * iOS clock receipts have archive file protection and are excluded from device backups. A receipt is
 * Durable and immutable: written once with [AtomicFiles.createNoReplace] (#60).
 */
class IosClockAnchorStore(
    root: String,
    private val atomic: AtomicFiles = IosAtomicFiles(IosArchiveStore.tempDirBeside(root.trimEnd('/'))),
) : ClockAnchorStore {
    private val root = root.trimEnd('/')
    private val mutex = Mutex()

    override suspend fun anchors(station: String): List<ClockAnchor> = withContext(Dispatchers.IO) {
        require(ArchiveLayout.isSafeStation(station)) { "unsafe station id" }
        mutex.withLock {
            val directory = checked("$root/$station")
            val anchors = IosFiles.walk(directory).mapNotNull { relative ->
                if (relative.count { it == '/' } != 1 || !relative.endsWith(".anchor")) return@mapNotNull null
                val path = checked("$directory/$relative")
                val bytes = IosFiles.readSmall(path, ClockAnchorReceipt.MAX_BYTES) ?: return@mapNotNull null
                ClockAnchorReceipt.decode(bytes, station, relative)
            }
            ClockAnchorReceipt.earliest(anchors)
        }
    }

    override suspend fun put(anchor: ClockAnchor) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val path = checked("$root/${anchor.station}/${ClockAnchorReceipt.relativePath(anchor)}")
            IosFiles.prepareRoot(root)
            // AtomicFiles creates the station and boot directories and syncs each new level.
            val bytes = ClockAnchorReceipt.encode(anchor)
            try {
                atomic.createNoReplace(path, bytes, StorageClass.Durable)
            } catch (_: AlreadyCommitted) {
                check(IosFiles.readSmall(path, ClockAnchorReceipt.MAX_BYTES)?.contentEquals(bytes) == true) { "conflicting clock receipt" }
            }
        }
    }

    private fun checked(path: String): String {
        check(IosFiles.kind(root) != IosFiles.Kind.LINK) { "refusing a symbolic link in clock receipts" }
        IosFiles.checkNoLinks(path, root)
        return path
    }
}
