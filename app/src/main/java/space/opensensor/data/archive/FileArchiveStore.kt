package space.opensensor.data.archive

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import space.opensensor.archive.ArchiveKey
import space.opensensor.archive.ArchiveLayout
import space.opensensor.archive.ArchiveStore
import space.opensensor.archive.PartialMeta
import space.opensensor.archive.PartialWriter
import space.opensensor.archive.openArchiveFile
import space.opensensor.data.coroutines.IoDispatcher
import space.opensensor.data.history.parquet.ByteReader
import space.opensensor.data.sync.FileArchive
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The shared [ArchiveStore] over Android's [FileArchive] in `filesDir`. File I/O only, on [io]. The
 * layout is [ArchiveLayout]'s, which `FileArchive` already writes (`archive/`, `archive-partial/`,
 * `"<size> <crc32>"` meta).
 */
@Singleton
class FileArchiveStore @Inject constructor(
    private val archive: FileArchive,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ArchiveStore {
    /** The directory both `archive/` and `archive-partial/` live in (`filesDir`). */
    private val base: File get() = checkNotNull(archive.root.parentFile)

    override suspend fun partialLengths(thing: String): Map<String, Long> = withContext(io) {
        archive.partialLengths(thing).filterKeys(ArchiveLayout::isArchiveName)
    }

    override suspend fun openPartial(key: ArchiveKey, meta: PartialMeta): PartialWriter = withContext(io) {
        val (writer, resumeFrom) = archive.openPartial(key.thing, key.name, meta.size, meta.crc32)
        object : PartialWriter {
            override val resumeFrom: Long = resumeFrom
            override fun writeAt(offset: Long, payload: ByteArray) = writer.writeAt(offset, payload)
            override fun length(): Long = writer.length()
            override fun sync() = writer.sync()
            override fun close() = writer.close()
        }
    }

    override suspend fun discardPartial(key: ArchiveKey) = withContext(io) { archive.discardPartial(key.thing, key.name) }

    override suspend fun exists(key: ArchiveKey): Boolean = withContext(io) { archive.exists(key.thing, key.name) }

    override fun reader(key: ArchiveKey, partial: Boolean): ByteReader =
        openArchiveFile(File(base, if (partial) ArchiveLayout.partialPath(key) else ArchiveLayout.finalPath(key)))

    override suspend fun commit(key: ArchiveKey) {
        withContext(io) { archive.commit(key.thing, key.name) }
    }

    override suspend fun listFinal(thing: String?): List<ArchiveKey> = withContext(io) {
        archive.listAll().mapNotNull { (s, name, _) ->
            if (thing != null && s != thing) return@mapNotNull null
            if (ArchiveLayout.isSafeThing(s) && ArchiveLayout.isArchiveName(name)) ArchiveKey(s, name) else null
        }
    }
}
