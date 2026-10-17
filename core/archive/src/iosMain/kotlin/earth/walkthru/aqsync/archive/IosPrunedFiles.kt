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

    override fun observeAll(): Flow<List<PrunedFile>> = snapshot.onStart { loaded() }.filterNotNull()

    override suspend fun put(file: PrunedFile) = change { all -> all.filterNot { it.key == file.key } + file }

    override suspend fun remove(key: ArchiveKey) = change { all -> all.filterNot { it.key == key } }

    override suspend fun clear(): Int {
        var cleared = 0
        change { all ->
            cleared = all.size
            emptyList()
        }
        return cleared
    }

    private suspend fun loaded(): List<PrunedFile> = mutex.withLock { snapshot.value ?: read().also { snapshot.value = it } }

    private suspend fun change(update: (List<PrunedFile>) -> List<PrunedFile>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val next = update(snapshot.value ?: read())
            IosFiles.prepareRoot(root)
            checked()
            atomic.writeAtomically(path, PrunedFilesCodec.encode(next), StorageClass.Durable)
            snapshot.value = next
        }
    }

    private suspend fun read(): List<PrunedFile> = withContext(Dispatchers.IO) {
        if (IosFiles.kind(path) == IosFiles.Kind.ABSENT) return@withContext emptyList()
        checked()
        IosFiles.readSmall(path, PrunedFilesCodec.MAX_BYTES)?.let(PrunedFilesCodec::decode).orEmpty()
    }

    private fun checked() {
        check(IosFiles.kind(root) != IosFiles.Kind.LINK) { "refusing a symbolic link in removed files" }
        IosFiles.checkNoLinks(path, root)
    }

    private companion object {
        const val FILE = "pruned.v1"
    }
}
