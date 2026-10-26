package space.opensensor.archive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import space.opensensor.storage.AlreadyCommitted
import space.opensensor.storage.AtomicFiles
import space.opensensor.storage.IosAtomicFiles
import space.opensensor.storage.StorageClass

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

    override suspend fun anchors(thing: String): List<ClockAnchor> = withContext(Dispatchers.IO) {
        require(ArchiveLayout.isSafeThing(thing)) { "unsafe thing id" }
        mutex.withLock {
            val directory = checked("$root/$thing")
            val anchors = IosFiles.walk(directory).mapNotNull { relative ->
                if (relative.count { it == '/' } != 1 || !relative.endsWith(".anchor")) return@mapNotNull null
                val path = checked("$directory/$relative")
                val bytes = IosFiles.readSmall(path, ClockAnchorReceipt.MAX_BYTES) ?: return@mapNotNull null
                ClockAnchorReceipt.decode(bytes, thing, relative)
            }
            ClockAnchorReceipt.earliest(anchors)
        }
    }

    override suspend fun put(anchor: ClockAnchor) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val path = checked("$root/${anchor.thing}/${ClockAnchorReceipt.relativePath(anchor)}")
            IosFiles.prepareRoot(root)
            // AtomicFiles creates the thing and boot directories and syncs each new level.
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
