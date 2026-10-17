package earth.walkthru.aqsync.archive

import earth.walkthru.aqsync.storage.AtomicFiles
import earth.walkthru.aqsync.storage.IosAtomicFiles
import earth.walkthru.aqsync.storage.StorageClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * iOS [PrunedFiles]. One small Durable file under [root], outside the station directories, so the
 * janitor never adopts it. Each change rewrites the whole file atomically.
 *
 * A file that cannot be read whole (over 8 MB, a bad line, an unknown version, or no access yet)
 * reads as no records, and [put] and [remove] throw until a read succeeds, so they never rewrite it
 * from a partial list. [clear] writes an empty file without reading it, which is the way back.
 */
class IosPrunedFiles(
    root: String,
    private val atomic: AtomicFiles = IosAtomicFiles(IosArchiveStore.tempDirBeside(root.trimEnd('/'))),
) : PrunedFiles {
    private val root = root.trimEnd('/')
    private val path = "${this.root}/$FILE"
    private val mutex = Mutex()

    /** Null until the file is read once. */
    private val snapshot = MutableStateFlow<List<PrunedFile>?>(null)

    /** The last read failed, so [snapshot] is not the file. Guarded by [mutex]. */
    private var unreadable = false

    override fun observeAll(): Flow<List<PrunedFile>> = snapshot.onStart { loaded() }.filterNotNull()

    override suspend fun put(file: PrunedFile) = change { all -> all.filterNot { it.key == file.key } + file }

    override suspend fun remove(key: ArchiveKey) = change { all -> all.filterNot { it.key == key } }

    override suspend fun clear(): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            val cleared = if (unreadable) 0 else (snapshot.value ?: read()).orEmpty().size
            write(emptyList())
            cleared
        }
    }

    private suspend fun loaded() {
        mutex.withLock { if (snapshot.value == null) current() }
    }

    private suspend fun change(update: (List<PrunedFile>) -> List<PrunedFile>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val all = checkNotNull(current()) { "removed files record unreadable" }
            write(update(all))
        }
    }

    /** The cached records, or a fresh read while none is cached or the last read failed. */
    private suspend fun current(): List<PrunedFile>? {
        snapshot.value?.takeUnless { unreadable }?.let { return it }
        val read = read()
        unreadable = read == null
        snapshot.value = read ?: snapshot.value ?: emptyList()
        return read
    }

    private fun write(next: List<PrunedFile>) {
        IosFiles.prepareRoot(root)
        checked()
        atomic.writeAtomically(path, PrunedFilesCodec.encode(next), StorageClass.Durable)
        unreadable = false
        snapshot.value = next
    }

    /** The records, or null when the file is there but cannot be read whole. */
    private suspend fun read(): List<PrunedFile>? = withContext(Dispatchers.IO) {
        if (IosFiles.kind(path) == IosFiles.Kind.ABSENT) return@withContext emptyList()
        checked()
        IosFiles.readSmall(path, PrunedFilesCodec.MAX_BYTES)?.let(PrunedFilesCodec::decode)
    }

    private fun checked() {
        check(IosFiles.kind(root) != IosFiles.Kind.LINK) { "refusing a symbolic link in removed files" }
        IosFiles.checkNoLinks(path, root)
    }

    private companion object {
        const val FILE = "pruned.v1"
    }
}
