package earth.walkthru.aqsync.data.archive

import earth.walkthru.aqsync.archive.ArchiveKey
import earth.walkthru.aqsync.archive.ArchiveLayout
import earth.walkthru.aqsync.archive.ArchiveStore
import earth.walkthru.aqsync.archive.PartialMeta
import earth.walkthru.aqsync.archive.PartialWriter
import earth.walkthru.aqsync.data.coroutines.IoDispatcher
import earth.walkthru.aqsync.data.history.parquet.ByteReader
import earth.walkthru.aqsync.data.history.parquet.MappedFileReader
import earth.walkthru.aqsync.data.sync.FileArchive
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
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

    override suspend fun partialLengths(station: String): Map<String, Long> = withContext(io) {
        archive.partialLengths(station).filterKeys(ArchiveLayout::isArchiveName)
    }

    override suspend fun openPartial(key: ArchiveKey, meta: PartialMeta): PartialWriter = withContext(io) {
        val (writer, resumeFrom) = archive.openPartial(key.station, key.name, meta.size, meta.crc32)
        object : PartialWriter {
            override val resumeFrom: Long = resumeFrom
            override fun writeAt(offset: Long, payload: ByteArray) = writer.writeAt(offset, payload)
            override fun length(): Long = writer.length()
            override fun sync() = writer.sync()
            override fun close() = writer.close()
        }
    }

    override suspend fun discardPartial(key: ArchiveKey) = withContext(io) { archive.discardPartial(key.station, key.name) }

    override suspend fun exists(key: ArchiveKey): Boolean = withContext(io) { archive.exists(key.station, key.name) }

    override fun reader(key: ArchiveKey, partial: Boolean): ByteReader =
        MappedFileReader(File(base, if (partial) ArchiveLayout.partialPath(key) else ArchiveLayout.finalPath(key)))

    override suspend fun commit(key: ArchiveKey) {
        withContext(io) { archive.commit(key.station, key.name) }
    }

    override suspend fun listFinal(station: String?): List<ArchiveKey> = withContext(io) {
        archive.listAll().mapNotNull { (s, name, _) ->
            if (station != null && s != station) return@mapNotNull null
            if (ArchiveLayout.isSafeStation(s) && ArchiveLayout.isArchiveName(name)) ArchiveKey(s, name) else null
        }
    }

    override suspend fun delete(key: ArchiveKey): Boolean = withContext(io) { archive.delete(key.station, key.name) }
}
