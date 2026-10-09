package earth.walkthru.aqsync.archive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** iOS clock receipts have archive file protection and are excluded from device backups. */
class IosClockAnchorStore(root: String) : ClockAnchorStore {
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
            IosFiles.mkdirs(path.substringBeforeLast('/'))
            val bytes = ClockAnchorReceipt.encode(anchor)
            if (IosFiles.kind(path) != IosFiles.Kind.ABSENT) {
                check(IosFiles.readSmall(path, ClockAnchorReceipt.MAX_BYTES)?.contentEquals(bytes) == true) { "conflicting clock receipt" }
            } else {
                IosFiles.writeAtomically(path, bytes)
            }
        }
    }

    private fun checked(path: String): String {
        check(IosFiles.kind(root) != IosFiles.Kind.LINK) { "refusing a symbolic link in clock receipts" }
        IosFiles.checkNoLinks(path, root)
        return path
    }
}
